import { Capacitor, registerPlugin } from '@capacitor/core';

type BackupResult = { success: boolean; createdAt: number };

type VaultPlugin = {
  getStatus(): Promise<{ configured: boolean; legacyVaultPresent: boolean; recoveryEnabled: boolean; autofillAvailable: boolean; autofillEnabled: boolean; pendingAutofillSave: boolean }>;
  setupPin(options: { pin: string }): Promise<{ success: boolean; recoveryCode?: string }>;
  unlockWithPin(options: { pin: string }): Promise<{ success: boolean; recoveryCode?: string }>;
  recoverWithCode(options: { recoveryCode: string; newPin: string }): Promise<{ success: boolean }>;
  exportRecoveryCode(options: { code: string }): Promise<{ success: boolean }>;
  exportBackup(): Promise<BackupResult>;
  chooseBackup(): Promise<BackupResult>;
  restoreBackup(options: { recoveryCode: string; newPin: string }): Promise<{ success: boolean }>;
  openAutofillSettings(): Promise<void>;
  getPendingAutofillSave(): Promise<{ pending: boolean; locked?: boolean; token?: string; title?: string; username?: string; password?: string }>;
  clearPendingAutofillSave(options: { token: string }): Promise<{ success: boolean }>;
  lock(): Promise<void>;
  readVault(): Promise<{ data: string }>;
  writeVault(options: { data: string }): Promise<void>;
  copySecret(options: { value: string; label: string; clearAfterSeconds?: number }): Promise<void>;
};

type BackupDocument = {
  format: 'pocket-vault-backup';
  version: 1;
  createdAt: number;
  kdfIterations: 180000;
  recoveryKdf: 'PBKDF2WithHmacSHA256';
  recoverySalt: string;
  recoveryWrappedMasterKey: string;
  encryptedVault: string;
};

const NativeVault = registerPlugin<VaultPlugin>('NativeVault');
const encoder = new TextEncoder();
const decoder = new TextDecoder();
let previewPin: string | null = null;
let previewRecoveryCode: string | null = null;
let previewRecoverySalt: Uint8Array<ArrayBuffer> | null = null;
let previewRecoveryWrappedMaster: Uint8Array<ArrayBuffer> | null = null;
let previewMaster: Uint8Array<ArrayBuffer> | null = null;
let previewUnlocked = false;
let pendingPreviewBackup: BackupDocument | null = null;
let previewAutofillEnabled = false;

function randomBytes(length: number): Uint8Array<ArrayBuffer> {
  return crypto.getRandomValues(new Uint8Array(length));
}

function createPreviewRecoveryCode() {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const bytes = randomBytes(24);
  const raw = Array.from(bytes, (byte) => alphabet[byte & 31]).join('');
  return `PV-${raw.match(/.{1,4}/g)!.join('-')}`;
}

function normalizeRecoveryCode(value: string) {
  return value.toUpperCase().replace(/[^A-Z0-9]/g, '');
}

function toBase64(bytes: Uint8Array) {
  let binary = '';
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
}

function fromBase64(value: string): Uint8Array<ArrayBuffer> {
  const binary = atob(value);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

async function recoveryKey(code: string, salt: Uint8Array<ArrayBuffer>) {
  const material = await crypto.subtle.importKey('raw', encoder.encode(normalizeRecoveryCode(code)), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', hash: 'SHA-256', salt, iterations: 180000 },
    material,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

async function masterKey(raw: Uint8Array<ArrayBuffer>) {
  return crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
}

async function encryptPacked(plaintext: Uint8Array<ArrayBuffer>, key: CryptoKey): Promise<Uint8Array<ArrayBuffer>> {
  const iv = randomBytes(12);
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, plaintext));
  const packed = new Uint8Array(4 + iv.length + ciphertext.length);
  new DataView(packed.buffer).setInt32(0, iv.length, false);
  packed.set(iv, 4);
  packed.set(ciphertext, 4 + iv.length);
  return packed;
}

async function decryptPacked(packed: Uint8Array<ArrayBuffer>, key: CryptoKey): Promise<Uint8Array<ArrayBuffer>> {
  if (packed.length < 17) throw new Error('Invalid encrypted data');
  const ivLength = new DataView(packed.buffer, packed.byteOffset, packed.byteLength).getInt32(0, false);
  if (ivLength < 12 || ivLength > 32 || packed.length <= 4 + ivLength) throw new Error('Invalid encrypted data');
  const iv = packed.slice(4, 4 + ivLength);
  const ciphertext = packed.slice(4 + ivLength);
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, ciphertext));
}

function parseBackup(value: string): BackupDocument {
  const parsed: unknown = JSON.parse(value);
  if (!parsed || typeof parsed !== 'object') throw new Error('Invalid backup');
  const backup = parsed as Partial<BackupDocument>;
  if (backup.format !== 'pocket-vault-backup' || backup.version !== 1 || backup.kdfIterations !== 180000 ||
      backup.recoveryKdf !== 'PBKDF2WithHmacSHA256' || typeof backup.recoverySalt !== 'string' ||
      typeof backup.recoveryWrappedMasterKey !== 'string' || typeof backup.encryptedVault !== 'string' ||
      typeof backup.createdAt !== 'number') throw new Error('Unsupported Pocket Vault backup');
  if (fromBase64(backup.recoverySalt).length !== 16 || fromBase64(backup.recoveryWrappedMasterKey).length < 40 ||
      fromBase64(backup.encryptedVault).length < 17) throw new Error('Invalid backup contents');
  return backup as BackupDocument;
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

async function copyPreviewText(value: string) {
  try {
    await navigator.clipboard.writeText(value);
    return;
  } catch {
    const field = document.createElement('textarea');
    field.value = value;
    field.readOnly = true;
    field.style.position = 'fixed';
    field.style.opacity = '0';
    field.style.pointerEvents = 'none';
    document.body.appendChild(field);
    field.select();
    field.setSelectionRange(0, field.value.length);
    const copied = document.execCommand('copy');
    field.remove();
    if (!copied) throw new Error('Clipboard access is unavailable');
  }
}

const browserVault: VaultPlugin = {
  async getStatus() {
    return {
      configured: previewPin !== null,
      legacyVaultPresent: false,
      recoveryEnabled: previewRecoveryCode !== null,
      autofillAvailable: true,
      autofillEnabled: previewAutofillEnabled,
      pendingAutofillSave: false,
    };
  },
  async setupPin({ pin }) {
    previewPin = pin;
    previewRecoveryCode = createPreviewRecoveryCode();
    previewRecoverySalt = randomBytes(16);
    previewMaster = randomBytes(32);
    previewRecoveryWrappedMaster = await encryptPacked(previewMaster, await recoveryKey(previewRecoveryCode, previewRecoverySalt));
    previewUnlocked = true;
    sessionStorage.setItem('pocket-vault-preview', '[]');
    return { success: true, recoveryCode: previewRecoveryCode };
  },
  async unlockWithPin({ pin }) {
    if (pin !== previewPin) throw new Error('Incorrect PIN');
    previewUnlocked = true;
    return { success: true };
  },
  async recoverWithCode({ recoveryCode, newPin }) {
    if (!previewRecoverySalt || !previewRecoveryWrappedMaster) throw new Error('Recovery is unavailable');
    try {
      previewMaster = await decryptPacked(previewRecoveryWrappedMaster, await recoveryKey(recoveryCode, previewRecoverySalt));
    } catch {
      throw new Error('Recovery code is incorrect');
    }
    previewRecoveryCode = recoveryCode;
    previewPin = newPin;
    previewUnlocked = true;
    return { success: true };
  },
  async exportRecoveryCode({ code }) {
    const text = `Pocket Vault Recovery Code\n\n${code}\n\nKeep this file private. Use it to reset your Pocket Vault PIN.\n`;
    downloadBlob(new Blob([text], { type: 'text/plain' }), 'Pocket-Vault-Recovery-Code.txt');
    return { success: true };
  },
  async exportBackup() {
    if (!previewUnlocked || !previewMaster || !previewRecoverySalt || !previewRecoveryWrappedMaster) throw new Error('Unlock the vault first');
    const createdAt = Date.now();
    const encryptedVault = await encryptPacked(encoder.encode(sessionStorage.getItem('pocket-vault-preview') ?? '[]'), await masterKey(previewMaster));
    const backup: BackupDocument = {
      format: 'pocket-vault-backup', version: 1, createdAt, kdfIterations: 180000,
      recoveryKdf: 'PBKDF2WithHmacSHA256', recoverySalt: toBase64(previewRecoverySalt),
      recoveryWrappedMasterKey: toBase64(previewRecoveryWrappedMaster), encryptedVault: toBase64(encryptedVault),
    };
    downloadBlob(new Blob([JSON.stringify(backup)], { type: 'application/octet-stream' }), 'Pocket-Vault-Backup.pvault');
    return { success: true, createdAt };
  },
  async chooseBackup() {
    const file = await new Promise<File>((resolve, reject) => {
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = '.pvault,application/octet-stream,application/json';
      input.onchange = () => input.files?.[0] ? resolve(input.files[0]) : reject(new Error('Backup selection cancelled'));
      input.click();
    });
    pendingPreviewBackup = parseBackup(await file.text());
    return { success: true, createdAt: pendingPreviewBackup.createdAt };
  },
  async restoreBackup({ recoveryCode, newPin }) {
    if (!pendingPreviewBackup) throw new Error('Choose a backup file again');
    try {
      const salt = fromBase64(pendingPreviewBackup.recoverySalt);
      const recoveredMaster = await decryptPacked(fromBase64(pendingPreviewBackup.recoveryWrappedMasterKey), await recoveryKey(recoveryCode, salt));
      const data = decoder.decode(await decryptPacked(fromBase64(pendingPreviewBackup.encryptedVault), await masterKey(recoveredMaster)));
      if (!Array.isArray(JSON.parse(data))) throw new Error('Invalid vault data');
      previewMaster = recoveredMaster;
      previewRecoverySalt = salt;
      previewRecoveryWrappedMaster = fromBase64(pendingPreviewBackup.recoveryWrappedMasterKey);
      previewRecoveryCode = recoveryCode;
      previewPin = newPin;
      previewUnlocked = true;
      sessionStorage.setItem('pocket-vault-preview', data);
      pendingPreviewBackup = null;
      return { success: true };
    } catch {
      throw new Error('Recovery code is incorrect or the backup is damaged');
    }
  },
  async openAutofillSettings() { previewAutofillEnabled = true; },
  async getPendingAutofillSave() { return { pending: false }; },
  async clearPendingAutofillSave() { return { success: true }; },
  async lock() { previewUnlocked = false; },
  async readVault() {
    if (!previewUnlocked) throw new Error('Vault is locked');
    return { data: sessionStorage.getItem('pocket-vault-preview') ?? '[]' };
  },
  async writeVault({ data }) {
    if (!previewUnlocked) throw new Error('Vault is locked');
    sessionStorage.setItem('pocket-vault-preview', data);
  },
  async copySecret({ value }) { await copyPreviewText(value); },
};

export const vault = Capacitor.getPlatform() === 'android' ? NativeVault : browserVault;
export const isAndroid = Capacitor.getPlatform() === 'android';
