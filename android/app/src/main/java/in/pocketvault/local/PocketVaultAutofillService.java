package in.pocketvault.local;

import android.app.PendingIntent;
import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.text.InputType;
import android.view.View;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PocketVaultAutofillService extends AutofillService {
    private static final String ACTION_SAVE_CAPTURED = "in.pocketvault.local.SAVE_CAPTURED_LOGIN";
    private static final String EXTRA_CAPTURE_TOKEN = "capture_token";

    private static final class DetectedFields {
        final List<AutofillId> usernames = new ArrayList<>();
        final List<AutofillId> passwords = new ArrayList<>();
    }

    private static final class CapturedValues {
        String username = "";
        String password = "";
        String newPassword = "";
        String webDomain = "";
    }

    @Override
    public void onFillRequest(FillRequest request, CancellationSignal cancellationSignal, FillCallback callback) {
        if (cancellationSignal.isCanceled() || request.getFillContexts().isEmpty()) {
            callback.onSuccess(null);
            return;
        }
        AssistStructure structure = request.getFillContexts().get(request.getFillContexts().size() - 1).getStructure();
        ComponentName activity = structure.getActivityComponent();
        if (activity != null && getPackageName().equals(activity.getPackageName())) {
            callback.onSuccess(null);
            return;
        }

        DetectedFields fields = new DetectedFields();
        traverseForIds(structure, fields);
        if (fields.passwords.isEmpty()) {
            callback.onSuccess(null);
            return;
        }

        AutofillId[] required = fields.passwords.toArray(new AutofillId[0]);
        SaveInfo.Builder save = new SaveInfo.Builder(
            SaveInfo.SAVE_DATA_TYPE_USERNAME | SaveInfo.SAVE_DATA_TYPE_PASSWORD,
            required
        ).setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE);
        if (!fields.usernames.isEmpty()) save.setOptionalIds(fields.usernames.toArray(new AutofillId[0]));
        callback.onSuccess(new FillResponse.Builder().setSaveInfo(save.build()).build());
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        if (request.getFillContexts().isEmpty()) {
            callback.onFailure("No login fields were available");
            return;
        }
        FillContext latest = request.getFillContexts().get(request.getFillContexts().size() - 1);
        AssistStructure structure = latest.getStructure();
        CapturedValues values = new CapturedValues();
        traverseForValues(structure, values);
        String password = !values.newPassword.isEmpty() ? values.newPassword : values.password;
        if (password.isEmpty()) {
            callback.onFailure("No password was available to save");
            return;
        }

        String title = resolveTitle(structure, values.webDomain);
        AutofillCaptureStore.CapturedLogin captured = AutofillCaptureStore.put(
            truncate(title, 120),
            truncate(values.username, 512),
            truncate(password, 4096)
        );
        Intent intent = new Intent(this, MainActivity.class)
            .setAction(ACTION_SAVE_CAPTURED)
            .putExtra(EXTRA_CAPTURE_TOKEN, captured.token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                (int) (System.currentTimeMillis() & 0x7fffffff),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            callback.onSuccess(pendingIntent.getIntentSender());
        } else {
            startActivity(intent);
            callback.onSuccess();
        }
    }

    private void traverseForIds(AssistStructure structure, DetectedFields output) {
        for (int windowIndex = 0; windowIndex < structure.getWindowNodeCount(); windowIndex++) {
            traverseNodeForIds(structure.getWindowNodeAt(windowIndex).getRootViewNode(), output);
        }
    }

    private void traverseNodeForIds(AssistStructure.ViewNode node, DetectedFields output) {
        int kind = classify(node);
        AutofillId id = node.getAutofillId();
        if (id != null && node.getAutofillType() == View.AUTOFILL_TYPE_TEXT) {
            if (kind == 1) output.usernames.add(id);
            else if (kind == 2 || kind == 3) output.passwords.add(id);
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            traverseNodeForIds(node.getChildAt(index), output);
        }
    }

    private void traverseForValues(AssistStructure structure, CapturedValues output) {
        for (int windowIndex = 0; windowIndex < structure.getWindowNodeCount(); windowIndex++) {
            traverseNodeForValues(structure.getWindowNodeAt(windowIndex).getRootViewNode(), output);
        }
    }

    private void traverseNodeForValues(AssistStructure.ViewNode node, CapturedValues output) {
        if (output.webDomain.isEmpty() && node.getWebDomain() != null) output.webDomain = node.getWebDomain();
        AutofillValue value = node.getAutofillValue();
        if (value != null && value.isText() && value.getTextValue() != null) {
            String text = value.getTextValue().toString();
            int kind = classify(node);
            if (kind == 1 && output.username.isEmpty()) output.username = text;
            else if (kind == 3 && !text.isEmpty()) output.newPassword = text;
            else if (kind == 2 && output.password.isEmpty()) output.password = text;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            traverseNodeForValues(node.getChildAt(index), output);
        }
    }

    // 0 = unrelated, 1 = username/email, 2 = password, 3 = new password.
    private int classify(AssistStructure.ViewNode node) {
        StringBuilder clues = new StringBuilder();
        String[] hints = node.getAutofillHints();
        if (hints != null) for (String hint : hints) if (hint != null) clues.append(' ').append(hint);
        if (node.getHint() != null) clues.append(' ').append(node.getHint());
        if (node.getIdEntry() != null) clues.append(' ').append(node.getIdEntry());
        String value = clues.toString().toLowerCase(Locale.US);
        if (value.contains("newpassword") || value.contains("new_password") || value.contains("new-password")) return 3;
        if (value.contains("password") || value.contains("passwd") || value.contains("pwd")) return 2;
        if (value.contains("username") || value.contains("user_name") || value.contains("email") ||
            value.contains("login") || value.contains("account")) return 1;

        int inputType = node.getInputType();
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        if (inputClass == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
             variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
             variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) return 2;
        if (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return 2;
        if (inputClass == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
             variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) return 1;
        return 0;
    }

    private String resolveTitle(AssistStructure structure, String domain) {
        if (domain != null && !domain.trim().isEmpty()) return domain.trim();
        ComponentName activity = structure.getActivityComponent();
        if (activity == null) return "Saved login";
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(activity.getPackageName(), 0);
            CharSequence label = getPackageManager().getApplicationLabel(info);
            if (label != null && !label.toString().trim().isEmpty()) return label.toString().trim();
        } catch (Exception ignored) {
            // Fall back to package name.
        }
        return activity.getPackageName();
    }

    private String truncate(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
