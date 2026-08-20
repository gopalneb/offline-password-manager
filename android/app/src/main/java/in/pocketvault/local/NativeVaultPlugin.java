package in.pocketvault.local;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.autofill.AutofillManager;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

@CapacitorPlugin(name = "NativeVault")
public class NativeVaultPlugin extends Plugin {
    private static final String PREFS_NAME = "pocket_vault_pin_config";
    private static final String CONFIG_VERSION_KEY = "config_version";
    private static final int CONFIG_VERSION = 2;
    private static final String PIN_SALT_V2_KEY = "pin_salt_v2";
    private static final String PIN_KDF_V2_KEY = "pin_kdf_v2";
    private static final String PIN_WRAPPED_MASTER_KEY = "pin_wrapped_master_v2";
    private static final String RECOVERY_SALT_KEY = "recovery_salt_v2";
    private static final String RECOVERY_KDF_KEY = "recovery_kdf_v2";
    private static final String RECOVERY_WRAPPED_MASTER_KEY = "recovery_wrapped_master_v2";
    private static final String LEGACY_SALT_KEY = "salt";
    private static final String LEGACY_KDF_KEY = "kdf";
    private static final String FAILED_ATTEMPTS_KEY = "failed_attempts";
    private static final String LOCKOUT_UNTIL_KEY = "lockout_until";
    private static final String MASTER_VAULT_FILE = "vault.master.bin";
    private static final String LEGACY_PIN_VAULT_FILE = "vault.pin.bin";
    private static final String DEVICE_AUTH_VAULT_FILE = "vault.bin";
    private static final int KDF_ITERATIONS = 180_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BYTES = 32;
    private static final int KEY_BITS = 256;
    private static final int MAX_LOCKOUT_SECONDS = 15 * 60;
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String BACKUP_FORMAT = "pocket-vault-backup";
    private static final int BACKUP_VERSION = 1;
    private static final int MAX_BACKUP_BYTES = 10 * 1024 * 1024;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService cryptoExecutor = Executors.newSingleThreadExecutor();
    private volatile SecretKeySpec sessionKey;
    private volatile PendingBackup pendingBackup;

    private static final class PendingBackup {
        final long createdAt;
        final String recoveryKdf;
        final byte[] recoverySalt;
        final String recoveryWrappedMaster;
        final byte[] encryptedVault;

        PendingBackup(long createdAt, String recoveryKdf, byte[] recoverySalt, String recoveryWrappedMaster, byte[] encryptedVault) {
            this.createdAt = createdAt;
            this.recoveryKdf = recoveryKdf;
            this.recoverySalt = recoverySalt;
            this.recoveryWrappedMaster = recoveryWrappedMaster;
            this.encryptedVault = encryptedVault;
        }

        void clear() {
            Arrays.fill(recoverySalt, (byte) 0);
            Arrays.fill(encryptedVault, (byte) 0);
        }
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        SharedPreferences prefs = preferences();
        JSObject result = new JSObject();
        result.put("configured", isV2Configured(prefs) || isLegacyPinConfigured(prefs));
        result.put("legacyVaultPresent", new File(getContext().getFilesDir(), DEVICE_AUTH_VAULT_FILE).exists());
        result.put("recoveryEnabled", isV2Configured(prefs));
        result.put("autofillAvailable", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O);
        result.put("autofillEnabled", isAutofillEnabled());
        result.put("pendingAutofillSave", AutofillCaptureStore.peek() != null);
        call.resolve(result);
    }

    @PluginMethod
    public void openAutofillSettings(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            call.reject("Android Autofill requires Android 8 or newer");
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE);
            intent.setData(Uri.parse("package:" + getContext().getPackageName()));
            getActivity().startActivity(intent);
            call.resolve();
        } catch (Exception error) {
            call.reject("Could not open Android Autofill settings", error);
        }
    }

    @PluginMethod
    public void getPendingAutofillSave(PluginCall call) {
        AutofillCaptureStore.CapturedLogin captured = AutofillCaptureStore.peek();
        JSObject result = new JSObject();
        if (captured == null) {
            result.put("pending", false);
        } else if (sessionKey == null) {
            result.put("pending", true);
            result.put("locked", true);
        } else {
            result.put("pending", true);
            result.put("locked", false);
            result.put("token", captured.token);
            result.put("title", captured.title);
            result.put("username", captured.username);
            result.put("password", captured.password);
        }
        call.resolve(result);
    }

    @PluginMethod
    public void clearPendingAutofillSave(PluginCall call) {
        String token = call.getString("token", "");
        JSObject result = new JSObject();
        result.put("success", AutofillCaptureStore.clear(token));
        call.resolve(result);
    }

    @PluginMethod
    public void setupPin(PluginCall call) {
        String pin = call.getString("pin", "");
        if (!isValidPin(pin)) {
            call.reject("PIN must contain exactly 6 digits");
            return;
        }
        cryptoExecutor.execute(() -> {
            try {
                SharedPreferences prefs = preferences();
                if (isV2Configured(prefs) || isLegacyPinConfigured(prefs)) {
                    call.reject("Pocket Vault PIN is already configured");
                    return;
                }
                String recoveryCode = initializeV2(pin, "[]");
                JSObject result = new JSObject();
                result.put("success", true);
                result.put("recoveryCode", recoveryCode);
                call.resolve(result);
            } catch (Exception error) {
                sessionKey = null;
                call.reject("Could not create the encrypted vault", error);
            }
        });
    }

    @PluginMethod
    public void unlockWithPin(PluginCall call) {
        String pin = call.getString("pin", "");
        if (!isValidPin(pin)) {
            call.reject("Enter your 6-digit PIN");
            return;
        }
        cryptoExecutor.execute(() -> {
            SharedPreferences prefs = preferences();
            if (rejectIfLockedOut(call, prefs)) return;
            try {
                String recoveryCode = null;
                if (isV2Configured(prefs)) {
                    sessionKey = unlockMasterWithPin(pin, prefs);
                    readMasterVault(sessionKey);
                } else if (isLegacyPinConfigured(prefs)) {
                    String data = readLegacyPinVault(pin, prefs);
                    recoveryCode = initializeV2(pin, data);
                } else {
                    call.reject("Pocket Vault PIN has not been configured");
                    return;
                }

                clearFailedAttempts(prefs);
                JSObject result = new JSObject();
                result.put("success", true);
                if (recoveryCode != null) result.put("recoveryCode", recoveryCode);
                call.resolve(result);
            } catch (AEADBadTagException error) {
                sessionKey = null;
                rejectIncorrectPin(call, prefs);
            } catch (Exception error) {
                sessionKey = null;
                if (hasBadTagCause(error)) rejectIncorrectPin(call, prefs);
                else call.reject("Could not open the encrypted vault", error);
            }
        });
    }

    @PluginMethod
    public void recoverWithCode(PluginCall call) {
        String recoveryCode = normalizeRecoveryCode(call.getString("recoveryCode", ""));
        String newPin = call.getString("newPin", "");
        if (!isValidRecoveryCode(recoveryCode)) {
            call.reject("Enter the complete recovery code");
            return;
        }
        if (!isValidPin(newPin)) {
            call.reject("New PIN must contain exactly 6 digits");
            return;
        }

        cryptoExecutor.execute(() -> {
            SharedPreferences prefs = preferences();
            try {
                if (!isV2Configured(prefs)) {
                    call.reject("Recovery was not configured for this vault");
                    return;
                }
                SecretKeySpec master = unlockMasterWithRecovery(recoveryCode, prefs);
                readMasterVault(master);
                updatePinWrapper(newPin, master, prefs);
                sessionKey = master;
                clearFailedAttempts(prefs);
                JSObject result = new JSObject();
                result.put("success", true);
                call.resolve(result);
            } catch (AEADBadTagException error) {
                sessionKey = null;
                call.reject("Recovery code is incorrect");
            } catch (Exception error) {
                sessionKey = null;
                if (hasBadTagCause(error)) call.reject("Recovery code is incorrect");
                else call.reject("Could not recover the encrypted vault", error);
            }
        });
    }

    @PluginMethod
    public void exportRecoveryCode(PluginCall call) {
        String code = call.getString("code", "");
        if (!isValidRecoveryCode(normalizeRecoveryCode(code))) {
            call.reject("Recovery code is unavailable");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "Pocket-Vault-Recovery-Code.txt");
        startActivityForResult(call, intent, "recoveryFileResult");
    }

    @ActivityCallback
    private void recoveryFileResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            call.reject("File save cancelled");
            return;
        }
        Uri uri = result.getData().getData();
        String code = call.getString("code", "");
        String content = "Pocket Vault Recovery Code\n\n" + code +
            "\n\nUse this code only if you forget your six-digit Pocket Vault PIN.\n" +
            "Keep this file private. Anyone with this code and access to your phone can reset the vault PIN.\n" +
            "Pocket Vault stores all data locally and cannot recover this code for you.\n";
        try (OutputStream output = getContext().getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalStateException("Could not open selected file");
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.flush();
            JSObject response = new JSObject();
            response.put("success", true);
            call.resolve(response);
        } catch (Exception error) {
            call.reject("Could not save the recovery file", error);
        }
    }

    @PluginMethod
    public void exportBackup(PluginCall call) {
        if (sessionKey == null || !isV2Configured(preferences())) {
            call.reject("Unlock the vault before creating a backup");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "Pocket-Vault-Backup.pvault");
        startActivityForResult(call, intent, "backupFileResult");
    }

    @ActivityCallback
    private void backupFileResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            call.reject("Backup save cancelled");
            return;
        }
        Uri uri = result.getData().getData();
        cryptoExecutor.execute(() -> {
            try {
                SecretKeySpec key = sessionKey;
                if (key == null) {
                    call.reject("Vault locked before the backup was saved");
                    return;
                }
                long createdAt = System.currentTimeMillis();
                byte[] encryptedVault = readAll(new File(getContext().getFilesDir(), MASTER_VAULT_FILE));
                SharedPreferences prefs = preferences();
                JSONObject backup = new JSONObject();
                backup.put("format", BACKUP_FORMAT);
                backup.put("version", BACKUP_VERSION);
                backup.put("createdAt", createdAt);
                backup.put("kdfIterations", KDF_ITERATIONS);
                backup.put("recoveryKdf", prefs.getString(RECOVERY_KDF_KEY, "PBKDF2WithHmacSHA256"));
                backup.put("recoverySalt", prefs.getString(RECOVERY_SALT_KEY, ""));
                backup.put("recoveryWrappedMasterKey", prefs.getString(RECOVERY_WRAPPED_MASTER_KEY, ""));
                backup.put("encryptedVault", Base64.encodeToString(encryptedVault, Base64.NO_WRAP));
                Arrays.fill(encryptedVault, (byte) 0);
                try (OutputStream output = getContext().getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IllegalStateException("Could not open selected file");
                    output.write(backup.toString().getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
                JSObject response = new JSObject();
                response.put("success", true);
                response.put("createdAt", createdAt);
                call.resolve(response);
            } catch (Exception error) {
                call.reject("Could not save the encrypted backup", error);
            }
        });
    }

    @PluginMethod
    public void chooseBackup(PluginCall call) {
        SharedPreferences prefs = preferences();
        if (isV2Configured(prefs) || isLegacyPinConfigured(prefs)) {
            call.reject("Backup restore is available before creating a vault");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "application/octet-stream", "application/json", "text/plain" });
        startActivityForResult(call, intent, "backupOpenResult");
    }

    @ActivityCallback
    private void backupOpenResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            call.reject("Backup selection cancelled");
            return;
        }
        Uri uri = result.getData().getData();
        cryptoExecutor.execute(() -> {
            try (InputStream input = getContext().getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("Could not open selected backup");
                byte[] bytes = readAll(input, MAX_BACKUP_BYTES);
                PendingBackup parsed = parseBackup(new String(bytes, StandardCharsets.UTF_8));
                Arrays.fill(bytes, (byte) 0);
                PendingBackup previous = pendingBackup;
                pendingBackup = parsed;
                if (previous != null) previous.clear();
                JSObject response = new JSObject();
                response.put("success", true);
                response.put("createdAt", parsed.createdAt);
                call.resolve(response);
            } catch (Exception error) {
                call.reject("This is not a valid Pocket Vault backup", error);
            }
        });
    }

    @PluginMethod
    public void restoreBackup(PluginCall call) {
        String recoveryCode = normalizeRecoveryCode(call.getString("recoveryCode", ""));
        String newPin = call.getString("newPin", "");
        if (!isValidRecoveryCode(recoveryCode)) {
            call.reject("Enter the complete recovery code");
            return;
        }
        if (!isValidPin(newPin)) {
            call.reject("New PIN must contain exactly 6 digits");
            return;
        }
        cryptoExecutor.execute(() -> {
            PendingBackup backup = pendingBackup;
            if (backup == null) {
                call.reject("Choose a backup file again");
                return;
            }
            try {
                if (isV2Configured(preferences()) || isLegacyPinConfigured(preferences())) {
                    call.reject("A vault is already configured on this device");
                    return;
                }
                SecretKeySpec recoveryKey = deriveKey(recoveryCode, backup.recoverySalt, backup.recoveryKdf);
                SecretKeySpec master = unwrapMaster(backup.recoveryWrappedMaster, recoveryKey);
                String data = decryptString(Arrays.copyOf(backup.encryptedVault, backup.encryptedVault.length), master);
                new JSONArray(data);
                installImportedVault(data, newPin, master, backup);
                sessionKey = master;
                pendingBackup = null;
                backup.clear();
                JSObject response = new JSObject();
                response.put("success", true);
                call.resolve(response);
            } catch (AEADBadTagException error) {
                sessionKey = null;
                call.reject("Recovery code is incorrect");
            } catch (Exception error) {
                sessionKey = null;
                if (hasBadTagCause(error)) call.reject("Recovery code is incorrect");
                else call.reject("Could not restore this backup", error);
            }
        });
    }

    @PluginMethod
    public void lock(PluginCall call) {
        sessionKey = null;
        call.resolve();
    }

    @PluginMethod
    public void readVault(PluginCall call) {
        cryptoExecutor.execute(() -> {
            SecretKeySpec key = sessionKey;
            if (key == null) {
                call.reject("Vault is locked");
                return;
            }
            try {
                JSObject response = new JSObject();
                response.put("data", readMasterVault(key));
                call.resolve(response);
            } catch (Exception error) {
                call.reject("Could not decrypt the vault", error);
            }
        });
    }

    @PluginMethod
    public void writeVault(PluginCall call) {
        String data = call.getString("data");
        if (data == null) {
            call.reject("Missing vault data");
            return;
        }
        cryptoExecutor.execute(() -> {
            SecretKeySpec key = sessionKey;
            if (key == null) {
                call.reject("Vault is locked");
                return;
            }
            try {
                writeMasterVault(data, key);
                call.resolve();
            } catch (Exception error) {
                call.reject("Could not save the encrypted vault", error);
            }
        });
    }

    @PluginMethod
    public void copySecret(PluginCall call) {
        String value = call.getString("value");
        String label = call.getString("label", "Sensitive value");
        Integer seconds = call.getInt("clearAfterSeconds", 30);
        if (value == null || value.isEmpty()) {
            call.reject("Nothing to copy");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            call.reject("Clipboard is unavailable");
            return;
        }
        ClipData clip = ClipData.newPlainText(label, value);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        int delay = Math.max(5, Math.min(seconds == null ? 30 : seconds, 120));
        handler.postDelayed(() -> clearIfUnchanged(clipboard, value), delay * 1000L);
        call.resolve();
    }

    private String initializeV2(String pin, String data) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] masterBytes = new byte[KEY_BYTES];
        byte[] pinSalt = new byte[SALT_BYTES];
        byte[] recoverySalt = new byte[SALT_BYTES];
        random.nextBytes(masterBytes);
        random.nextBytes(pinSalt);
        random.nextBytes(recoverySalt);
        SecretKeySpec master = new SecretKeySpec(masterBytes, "AES");
        Arrays.fill(masterBytes, (byte) 0);

        String kdf = availableKdf();
        String recoveryCode = generateRecoveryCode(random);
        SecretKeySpec pinKey = deriveKey(pin, pinSalt, kdf);
        SecretKeySpec recoveryKey = deriveKey(normalizeRecoveryCode(recoveryCode), recoverySalt, kdf);
        String pinWrapped = wrapMaster(master, pinKey);
        String recoveryWrapped = wrapMaster(master, recoveryKey);
        writeMasterVault(data, master);

        boolean committed = preferences().edit()
            .putInt(CONFIG_VERSION_KEY, CONFIG_VERSION)
            .putString(PIN_SALT_V2_KEY, Base64.encodeToString(pinSalt, Base64.NO_WRAP))
            .putString(PIN_KDF_V2_KEY, kdf)
            .putString(PIN_WRAPPED_MASTER_KEY, pinWrapped)
            .putString(RECOVERY_SALT_KEY, Base64.encodeToString(recoverySalt, Base64.NO_WRAP))
            .putString(RECOVERY_KDF_KEY, kdf)
            .putString(RECOVERY_WRAPPED_MASTER_KEY, recoveryWrapped)
            .putInt(FAILED_ATTEMPTS_KEY, 0)
            .putLong(LOCKOUT_UNTIL_KEY, 0L)
            .commit();
        Arrays.fill(pinSalt, (byte) 0);
        Arrays.fill(recoverySalt, (byte) 0);
        if (!committed) throw new IllegalStateException("Could not save recovery configuration");
        sessionKey = master;
        return recoveryCode;
    }

    private PendingBackup parseBackup(String json) throws Exception {
        JSONObject backup = new JSONObject(json);
        if (!BACKUP_FORMAT.equals(backup.optString("format")) || backup.optInt("version", -1) != BACKUP_VERSION) {
            throw new IllegalStateException("Unsupported backup format");
        }
        if (backup.optInt("kdfIterations", -1) != KDF_ITERATIONS) {
            throw new IllegalStateException("Unsupported backup encryption settings");
        }
        String kdf = backup.getString("recoveryKdf");
        if (!"PBKDF2WithHmacSHA256".equals(kdf) && !"PBKDF2WithHmacSHA1".equals(kdf)) {
            throw new IllegalStateException("Unsupported recovery encryption");
        }
        byte[] salt = Base64.decode(backup.getString("recoverySalt"), Base64.NO_WRAP);
        byte[] wrapped = Base64.decode(backup.getString("recoveryWrappedMasterKey"), Base64.NO_WRAP);
        byte[] vault = Base64.decode(backup.getString("encryptedVault"), Base64.NO_WRAP);
        if (salt.length != SALT_BYTES || wrapped.length < 40 || vault.length < 17 || vault.length > MAX_BACKUP_BYTES) {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(wrapped, (byte) 0);
            Arrays.fill(vault, (byte) 0);
            throw new IllegalStateException("Invalid backup contents");
        }
        String wrappedBase64 = Base64.encodeToString(wrapped, Base64.NO_WRAP);
        Arrays.fill(wrapped, (byte) 0);
        return new PendingBackup(backup.optLong("createdAt", 0L), kdf, salt, wrappedBase64, vault);
    }

    private void installImportedVault(String data, String newPin, SecretKeySpec master, PendingBackup backup) throws Exception {
        byte[] pinSalt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(pinSalt);
        String pinKdf = availableKdf();
        SecretKeySpec pinKey = deriveKey(newPin, pinSalt, pinKdf);
        String pinWrapped = wrapMaster(master, pinKey);
        writeMasterVault(data, master);
        boolean committed = preferences().edit()
            .putInt(CONFIG_VERSION_KEY, CONFIG_VERSION)
            .putString(PIN_SALT_V2_KEY, Base64.encodeToString(pinSalt, Base64.NO_WRAP))
            .putString(PIN_KDF_V2_KEY, pinKdf)
            .putString(PIN_WRAPPED_MASTER_KEY, pinWrapped)
            .putString(RECOVERY_SALT_KEY, Base64.encodeToString(backup.recoverySalt, Base64.NO_WRAP))
            .putString(RECOVERY_KDF_KEY, backup.recoveryKdf)
            .putString(RECOVERY_WRAPPED_MASTER_KEY, backup.recoveryWrappedMaster)
            .putInt(FAILED_ATTEMPTS_KEY, 0)
            .putLong(LOCKOUT_UNTIL_KEY, 0L)
            .commit();
        Arrays.fill(pinSalt, (byte) 0);
        if (!committed) throw new IllegalStateException("Could not save the restored vault configuration");
    }

    private SecretKeySpec unlockMasterWithPin(String pin, SharedPreferences prefs) throws Exception {
        byte[] salt = decodePreference(prefs, PIN_SALT_V2_KEY);
        SecretKeySpec wrappingKey = deriveKey(pin, salt, prefs.getString(PIN_KDF_V2_KEY, "PBKDF2WithHmacSHA256"));
        Arrays.fill(salt, (byte) 0);
        return unwrapMaster(prefs.getString(PIN_WRAPPED_MASTER_KEY, ""), wrappingKey);
    }

    private SecretKeySpec unlockMasterWithRecovery(String code, SharedPreferences prefs) throws Exception {
        byte[] salt = decodePreference(prefs, RECOVERY_SALT_KEY);
        SecretKeySpec wrappingKey = deriveKey(code, salt, prefs.getString(RECOVERY_KDF_KEY, "PBKDF2WithHmacSHA256"));
        Arrays.fill(salt, (byte) 0);
        return unwrapMaster(prefs.getString(RECOVERY_WRAPPED_MASTER_KEY, ""), wrappingKey);
    }

    private void updatePinWrapper(String newPin, SecretKeySpec master, SharedPreferences prefs) throws Exception {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        String kdf = availableKdf();
        SecretKeySpec pinKey = deriveKey(newPin, salt, kdf);
        String wrapped = wrapMaster(master, pinKey);
        boolean committed = prefs.edit()
            .putString(PIN_SALT_V2_KEY, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(PIN_KDF_V2_KEY, kdf)
            .putString(PIN_WRAPPED_MASTER_KEY, wrapped)
            .commit();
        Arrays.fill(salt, (byte) 0);
        if (!committed) throw new IllegalStateException("Could not save the new PIN");
    }

    private SecretKeySpec unwrapMaster(String wrappedBase64, SecretKeySpec wrappingKey) throws Exception {
        byte[] packed = Base64.decode(wrappedBase64, Base64.NO_WRAP);
        byte[] masterBytes = decryptBytes(packed, wrappingKey);
        try {
            if (masterBytes.length != KEY_BYTES) throw new IllegalStateException("Invalid master key");
            return new SecretKeySpec(masterBytes, "AES");
        } finally {
            Arrays.fill(packed, (byte) 0);
            Arrays.fill(masterBytes, (byte) 0);
        }
    }

    private String wrapMaster(SecretKeySpec master, SecretKeySpec wrappingKey) throws Exception {
        byte[] masterBytes = master.getEncoded();
        byte[] packed = null;
        try {
            packed = encryptBytes(masterBytes, wrappingKey);
            return Base64.encodeToString(packed, Base64.NO_WRAP);
        } finally {
            Arrays.fill(masterBytes, (byte) 0);
            if (packed != null) Arrays.fill(packed, (byte) 0);
        }
    }

    private String readLegacyPinVault(String pin, SharedPreferences prefs) throws Exception {
        byte[] salt = decodePreference(prefs, LEGACY_SALT_KEY);
        SecretKeySpec key = deriveKey(pin, salt, prefs.getString(LEGACY_KDF_KEY, "PBKDF2WithHmacSHA256"));
        Arrays.fill(salt, (byte) 0);
        return decryptString(readAll(new File(getContext().getFilesDir(), LEGACY_PIN_VAULT_FILE)), key);
    }

    private String readMasterVault(SecretKeySpec master) throws Exception {
        return decryptString(readAll(new File(getContext().getFilesDir(), MASTER_VAULT_FILE)), master);
    }

    private void writeMasterVault(String data, SecretKeySpec master) throws Exception {
        byte[] plaintext = data.getBytes(StandardCharsets.UTF_8);
        byte[] packed = null;
        try {
            packed = encryptBytes(plaintext, master);
            File finalFile = new File(getContext().getFilesDir(), MASTER_VAULT_FILE);
            File tempFile = new File(getContext().getFilesDir(), MASTER_VAULT_FILE + ".tmp");
            try (FileOutputStream stream = new FileOutputStream(tempFile)) {
                stream.write(packed);
                stream.getFD().sync();
            }
            if (!tempFile.renameTo(finalFile)) {
                try (FileOutputStream stream = new FileOutputStream(finalFile)) {
                    stream.write(packed);
                    stream.getFD().sync();
                }
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            if (packed != null) Arrays.fill(packed, (byte) 0);
        }
    }

    private byte[] encryptBytes(byte[] plaintext, SecretKeySpec key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ciphertext = cipher.doFinal(plaintext);
        byte[] iv = cipher.getIV();
        ByteBuffer output = ByteBuffer.allocate(4 + iv.length + ciphertext.length);
        output.putInt(iv.length).put(iv).put(ciphertext);
        Arrays.fill(ciphertext, (byte) 0);
        Arrays.fill(iv, (byte) 0);
        return output.array();
    }

    private byte[] decryptBytes(byte[] packed, SecretKeySpec key) throws Exception {
        if (packed.length < 17) throw new IllegalStateException("Invalid encrypted data");
        ByteBuffer buffer = ByteBuffer.wrap(packed);
        int ivLength = buffer.getInt();
        if (ivLength < 12 || ivLength > 32 || buffer.remaining() <= ivLength) throw new IllegalStateException("Invalid encrypted data");
        byte[] iv = new byte[ivLength];
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return cipher.doFinal(ciphertext);
        } finally {
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }
    }

    private String decryptString(byte[] packed, SecretKeySpec key) throws Exception {
        byte[] plaintext = decryptBytes(packed, key);
        try {
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(packed, (byte) 0);
        }
    }

    private SecretKeySpec deriveKey(String material, byte[] salt, String algorithm) throws Exception {
        char[] chars = material.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, KDF_ITERATIONS, KEY_BITS);
        byte[] encoded = null;
        try {
            encoded = SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded();
            return new SecretKeySpec(encoded, "AES");
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
        }
    }

    private String generateRecoveryCode(SecureRandom random) {
        StringBuilder raw = new StringBuilder("PV");
        for (int i = 0; i < 24; i++) raw.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
        StringBuilder formatted = new StringBuilder("PV");
        for (int i = 2; i < raw.length(); i += 4) formatted.append('-').append(raw, i, Math.min(i + 4, raw.length()));
        return formatted.toString();
    }

    private String normalizeRecoveryCode(String value) {
        return value == null ? "" : value.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    }

    private boolean isValidRecoveryCode(String value) {
        return value != null && value.matches("PV[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{24}");
    }

    private boolean isValidPin(String pin) {
        return pin != null && pin.matches("\\d{6}");
    }

    private boolean isV2Configured(SharedPreferences prefs) {
        return prefs.getInt(CONFIG_VERSION_KEY, 0) == CONFIG_VERSION
            && prefs.contains(PIN_WRAPPED_MASTER_KEY)
            && prefs.contains(RECOVERY_WRAPPED_MASTER_KEY)
            && new File(getContext().getFilesDir(), MASTER_VAULT_FILE).exists();
    }

    private boolean isLegacyPinConfigured(SharedPreferences prefs) {
        return prefs.contains(LEGACY_SALT_KEY) && new File(getContext().getFilesDir(), LEGACY_PIN_VAULT_FILE).exists();
    }

    private boolean rejectIfLockedOut(PluginCall call, SharedPreferences prefs) {
        long remaining = prefs.getLong(LOCKOUT_UNTIL_KEY, 0L) - System.currentTimeMillis();
        if (remaining <= 0) return false;
        call.reject("Too many attempts. Try again in " + Math.max(1, (remaining + 999) / 1000) + " seconds");
        return true;
    }

    private void rejectIncorrectPin(PluginCall call, SharedPreferences prefs) {
        int attempts = prefs.getInt(FAILED_ATTEMPTS_KEY, 0) + 1;
        SharedPreferences.Editor editor = prefs.edit().putInt(FAILED_ATTEMPTS_KEY, attempts);
        if (attempts >= 5) {
            int delaySeconds = Math.min(30 * (1 << Math.min(attempts - 5, 5)), MAX_LOCKOUT_SECONDS);
            editor.putLong(LOCKOUT_UNTIL_KEY, System.currentTimeMillis() + delaySeconds * 1000L).apply();
            call.reject("Incorrect PIN. Try again in " + delaySeconds + " seconds");
        } else {
            editor.apply();
            call.reject("Incorrect PIN. " + (5 - attempts) + " attempts before a temporary lock");
        }
    }

    private void clearFailedAttempts(SharedPreferences prefs) {
        prefs.edit().putInt(FAILED_ATTEMPTS_KEY, 0).putLong(LOCKOUT_UNTIL_KEY, 0L).apply();
    }

    private boolean hasBadTagCause(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AEADBadTagException) return true;
            current = current.getCause();
        }
        return false;
    }

    private byte[] decodePreference(SharedPreferences prefs, String key) {
        return Base64.decode(prefs.getString(key, ""), Base64.NO_WRAP);
    }

    private String availableKdf() {
        try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return "PBKDF2WithHmacSHA256";
        } catch (Exception ignored) {
            return "PBKDF2WithHmacSHA1";
        }
    }

    private boolean isAutofillEnabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        AutofillManager manager = getContext().getSystemService(AutofillManager.class);
        return manager != null && manager.hasEnabledAutofillServices();
    }

    private SharedPreferences preferences() {
        return getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void clearIfUnchanged(ClipboardManager clipboard, String copiedValue) {
        try {
            ClipData current = clipboard.getPrimaryClip();
            if (current == null || current.getItemCount() == 0) return;
            CharSequence text = current.getItemAt(0).coerceToText(getContext());
            if (!copiedValue.contentEquals(text)) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip();
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
        } catch (SecurityException ignored) {
            // Some Android versions restrict background clipboard access.
        }
    }

    private byte[] readAll(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private byte[] readAll(InputStream input, int maximumBytes) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) throw new IllegalStateException("Backup file is too large");
                output.write(buffer, 0, count);
            }
            Arrays.fill(buffer, (byte) 0);
            return output.toByteArray();
        }
    }

    @Override
    protected void handleOnDestroy() {
        sessionKey = null;
        PendingBackup backup = pendingBackup;
        pendingBackup = null;
        if (backup != null) backup.clear();
        cryptoExecutor.shutdownNow();
        super.handleOnDestroy();
    }
}
