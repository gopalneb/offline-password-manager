import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Check, Copy, Delete, Download, Eye, EyeOff, KeyRound, LockKeyhole,
  Pencil, Plus, Search, Settings, ShieldCheck, Sparkles, Star, Trash2, Upload, UserRound, X,
} from 'lucide-react';
import { emptyEntry, type VaultEntry } from './types';
import { isAndroid, vault } from './nativeVault';

const COLORS = ['#c96f52', '#4d7d70', '#6f6aa8', '#c39745', '#527da1'];

function initials(title: string) {
  return title.trim().slice(0, 2).toUpperCase() || 'PW';
}

function generatePassword() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*?';
  const bytes = crypto.getRandomValues(new Uint8Array(20));
  return Array.from(bytes, (byte) => chars[byte % chars.length]).join('');
}

function parseEntries(data: string): VaultEntry[] {
  const value: unknown = JSON.parse(data);
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is VaultEntry => (
    typeof item === 'object' && item !== null &&
    typeof item.id === 'string' && typeof item.title === 'string' &&
    typeof item.username === 'string' && typeof item.password === 'string'
  ));
}

export default function App() {
  const [configured, setConfigured] = useState<boolean | null>(null);
  const [legacyVaultPresent, setLegacyVaultPresent] = useState(false);
  const [locked, setLocked] = useState(true);
  const [entries, setEntries] = useState<VaultEntry[]>([]);
  const [query, setQuery] = useState('');
  const [editor, setEditor] = useState<VaultEntry | null>(null);
  const [toast, setToast] = useState('');
  const [recoveryCode, setRecoveryCode] = useState<string | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [autofillAvailable, setAutofillAvailable] = useState(false);
  const [autofillEnabled, setAutofillEnabled] = useState(false);
  const [pendingAutofillSave, setPendingAutofillSave] = useState(false);
  const [pendingAutofillToken, setPendingAutofillToken] = useState<string | null>(null);
  const [lastBackup, setLastBackup] = useState<number | null>(() => {
    const value = Number(localStorage.getItem('pocket-vault-last-backup'));
    return Number.isFinite(value) && value > 0 ? value : null;
  });

  const lockVault = useCallback(() => {
    void vault.lock();
    setEntries([]);
    setQuery('');
    setEditor(null);
    setLocked(true);
  }, []);

  useEffect(() => {
    const refreshStatus = () => vault.getStatus()
      .then((status) => {
        setConfigured(status.configured);
        setLegacyVaultPresent(status.legacyVaultPresent);
        setAutofillAvailable(status.autofillAvailable);
        setAutofillEnabled(status.autofillEnabled);
        setPendingAutofillSave(status.pendingAutofillSave);
      })
      .catch(() => setConfigured(false));
    void refreshStatus();

    const lockWhenHidden = () => {
      if (document.hidden) lockVault();
      else void refreshStatus();
    };
    document.addEventListener('visibilitychange', lockWhenHidden);
    return () => document.removeEventListener('visibilitychange', lockWhenHidden);
  }, [lockVault]);

  useEffect(() => {
    if (locked) return;
    let timer = window.setTimeout(lockVault, 5 * 60 * 1000);
    const stayActive = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(lockVault, 5 * 60 * 1000);
    };
    window.addEventListener('pointerdown', stayActive);
    window.addEventListener('keydown', stayActive);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener('pointerdown', stayActive);
      window.removeEventListener('keydown', stayActive);
    };
  }, [lockVault, locked]);

  const filtered = useMemo(() => {
    const value = query.trim().toLowerCase();
    return [...entries]
      .filter((entry) => !value || [entry.title, entry.username].some((field) => field.toLowerCase().includes(value)))
      .sort((a, b) => Number(b.favorite) - Number(a.favorite) || b.updatedAt - a.updatedAt);
  }, [entries, query]);

  async function finishUnlock() {
    const { data } = await vault.readVault();
    setEntries(parseEntries(data));
    setLocked(false);
    const captured = await vault.getPendingAutofillSave();
    if (captured.pending && !captured.locked && captured.token && captured.password) {
      setPendingAutofillToken(captured.token);
      setPendingAutofillSave(true);
      setEditor({
        ...emptyEntry(),
        title: captured.title || 'Saved login',
        username: captured.username || '',
        password: captured.password,
      });
      showToast('Review the login Android captured');
    }
  }

  async function setupPin(pin: string) {
    const result = await vault.setupPin({ pin });
    if (result.recoveryCode) setRecoveryCode(result.recoveryCode);
    setConfigured(true);
    await finishUnlock();
  }

  async function unlockWithPin(pin: string) {
    const result = await vault.unlockWithPin({ pin });
    if (result.recoveryCode) setRecoveryCode(result.recoveryCode);
    await finishUnlock();
  }

  async function recoverWithCode(code: string, newPin: string) {
    await vault.recoverWithCode({ recoveryCode: code, newPin });
    await finishUnlock();
  }

  async function chooseBackup() {
    return vault.chooseBackup();
  }

  async function restoreBackup(code: string, newPin: string) {
    await vault.restoreBackup({ recoveryCode: code, newPin });
    setConfigured(true);
    await finishUnlock();
  }

  async function exportBackup() {
    const result = await vault.exportBackup();
    setLastBackup(result.createdAt);
    localStorage.setItem('pocket-vault-last-backup', String(result.createdAt));
    showToast('Encrypted backup saved');
  }

  async function persist(next: VaultEntry[]) {
    await vault.writeVault({ data: JSON.stringify(next) });
    setEntries(next);
  }

  async function save(entry: VaultEntry) {
    const updated = { ...entry, title: entry.title.trim(), updatedAt: new Date().getTime() };
    if (!updated.title || !updated.password) return;
    const next = entries.some((item) => item.id === updated.id)
      ? entries.map((item) => item.id === updated.id ? updated : item)
      : [updated, ...entries];
    await persist(next);
    if (pendingAutofillToken) {
      await vault.clearPendingAutofillSave({ token: pendingAutofillToken });
      setPendingAutofillToken(null);
      setPendingAutofillSave(false);
    }
    setEditor(null);
    showToast('Saved securely');
  }

  async function remove(id: string) {
    try {
      await persist(entries.filter((entry) => entry.id !== id));
      setEditor(null);
      showToast('Login deleted');
    } catch {
      showToast('Could not delete this login');
    }
  }

  async function copy(value: string, label: string) {
    if (!value) return;
    try {
      await vault.copySecret({ value, label, clearAfterSeconds: 30 });
      showToast(`${label} copied • clears in 30 sec`);
    } catch {
      showToast(`Could not copy ${label.toLowerCase()}`);
    }
  }

  function showToast(message: string) {
    setToast(message);
    window.setTimeout(() => setToast(''), 2600);
  }

  async function closeEditor() {
    if (pendingAutofillToken) {
      await vault.clearPendingAutofillSave({ token: pendingAutofillToken });
      setPendingAutofillToken(null);
      setPendingAutofillSave(false);
    }
    setEditor(null);
  }

  async function enableAutofill() {
    if (isAndroid) setSettingsOpen(false);
    await vault.openAutofillSettings();
    const status = await vault.getStatus();
    setAutofillEnabled(status.autofillEnabled);
  }

  if (configured === null) {
    return <main className="lock-screen"><div className="loading-mark"><LockKeyhole size={29} /></div></main>;
  }

  if (locked) {
    return (
      <PinGate
        configured={configured}
        legacyVaultPresent={legacyVaultPresent}
        onSetup={setupPin}
        onUnlock={unlockWithPin}
        onRecover={recoverWithCode}
        onChooseBackup={chooseBackup}
        onRestoreBackup={restoreBackup}
        pendingAutofillSave={pendingAutofillSave}
      />
    );
  }

  return (
    <main className="app-shell">
      <header>
        <div><p className="eyebrow">POCKET VAULT</p><h1>Good to see you.</h1></div>
        <div className="header-actions">
          <button className="icon-button" onClick={() => setSettingsOpen(true)} aria-label="Vault settings"><Settings size={20} /></button>
          <button className="icon-button lock-now" onClick={lockVault} aria-label="Lock vault"><LockKeyhole size={20} /></button>
        </div>
      </header>

      <section className="summary-card">
        <div className="summary-icon"><ShieldCheck size={25} /></div>
        <div><strong>{entries.length} {entries.length === 1 ? 'login' : 'logins'} protected</strong><span>Encrypted on this device</span></div>
        <div className="status-dot" />
      </section>

      <label className="search-box">
        <Search size={19} />
        <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search your vault" />
        {query && <button onClick={() => setQuery('')} aria-label="Clear search"><X size={17} /></button>}
      </label>

      <section className="vault-section">
        <div className="section-title"><h2>Your logins</h2><span>{filtered.length}</span></div>
        {filtered.length === 0 ? (
          <div className="empty-state">
            <div><KeyRound size={29} /></div>
            <h3>{query ? 'Nothing found' : 'Your vault is ready'}</h3>
            <p>{query ? 'Try a different search.' : 'Add your first login. It will be encrypted before it is saved.'}</p>
            {!query && <button onClick={() => setEditor(emptyEntry())}><Plus size={18} /> Add first login</button>}
          </div>
        ) : filtered.map((entry, index) => (
          <article className="login-card" key={entry.id}>
            <button className="entry-main" onClick={() => setEditor(entry)}>
              <span className="entry-logo" style={{ background: COLORS[index % COLORS.length] }}>{initials(entry.title)}</span>
              <span className="entry-text"><strong>{entry.title}</strong><small>{entry.username || 'No username'}</small></span>
              {entry.favorite && <Star className="favorite" size={17} fill="currentColor" />}
            </button>
            <div className="quick-actions">
              <button onClick={() => copy(entry.username, 'Username')} disabled={!entry.username} aria-label={`Copy username for ${entry.title}`}><UserRound size={17} /><span>User</span></button>
              <button onClick={() => copy(entry.password, 'Password')} aria-label={`Copy password for ${entry.title}`}><KeyRound size={17} /><span>Password</span></button>
            </div>
          </article>
        ))}
      </section>

      <button className="fab" onClick={() => setEditor(emptyEntry())} aria-label="Add login"><Plus size={25} /></button>
      {editor && <EntryEditor entry={editor} captured={Boolean(pendingAutofillToken)} onClose={closeEditor} onSave={save} onDelete={remove} />}
      {settingsOpen && <BackupSheet lastBackup={lastBackup} autofillAvailable={autofillAvailable} autofillEnabled={autofillEnabled} onEnableAutofill={enableAutofill} onExport={exportBackup} onClose={() => setSettingsOpen(false)} />}
      {recoveryCode && <RecoveryCodeSheet code={recoveryCode} onClose={() => setRecoveryCode(null)} />}
      {toast && <div className="toast"><Check size={17} />{toast}</div>}
    </main>
  );
}

function PinGate({ configured, legacyVaultPresent, pendingAutofillSave, onSetup, onUnlock, onRecover, onChooseBackup, onRestoreBackup }: {
  configured: boolean;
  legacyVaultPresent: boolean;
  pendingAutofillSave: boolean;
  onSetup: (pin: string) => Promise<void>;
  onUnlock: (pin: string) => Promise<void>;
  onRecover: (code: string, newPin: string) => Promise<void>;
  onChooseBackup: () => Promise<{ success: boolean; createdAt: number }>;
  onRestoreBackup: (code: string, newPin: string) => Promise<void>;
}) {
  type PinPhase = 'create' | 'confirm' | 'unlock' | 'recover' | 'new-pin' | 'confirm-new' | 'restore-code' | 'restore-new-pin' | 'restore-confirm-new';
  const [phase, setPhase] = useState<PinPhase>(configured ? 'unlock' : 'create');
  const [pin, setPin] = useState('');
  const [firstPin, setFirstPin] = useState('');
  const [recoveryInput, setRecoveryInput] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [backupDate, setBackupDate] = useState<number | null>(null);

  function digit(value: string) {
    if (busy || pin.length >= 6) return;
    setPin((current) => current + value);
    setError('');
  }

  async function submit() {
    if (busy) return;
    if (phase === 'recover' || phase === 'restore-code') {
      if (recoveryInput.replace(/[^A-Za-z0-9]/g, '').length !== 26) {
        setError('Enter the complete recovery code.');
        return;
      }
      setPhase(phase === 'recover' ? 'new-pin' : 'restore-new-pin');
      setError('');
      return;
    }
    if (pin.length !== 6) return;
    if (phase === 'create' || phase === 'new-pin' || phase === 'restore-new-pin') {
      setFirstPin(pin);
      setPin('');
      setPhase(phase === 'create' ? 'confirm' : phase === 'new-pin' ? 'confirm-new' : 'restore-confirm-new');
      return;
    }
    if ((phase === 'confirm' || phase === 'confirm-new' || phase === 'restore-confirm-new') && pin !== firstPin) {
      setPin('');
      setError('PINs did not match. Please try again.');
      return;
    }

    setBusy(true);
    setError('');
    try {
      if (phase === 'confirm') await onSetup(pin);
      else if (phase === 'confirm-new') await onRecover(recoveryInput, pin);
      else if (phase === 'restore-confirm-new') await onRestoreBackup(recoveryInput, pin);
      else await onUnlock(pin);
      setPin('');
      setFirstPin('');
    } catch (caught) {
      setPin('');
      setError(caught instanceof Error ? caught.message : 'Could not unlock the vault');
    } finally {
      setBusy(false);
    }
  }

  async function selectBackup() {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      const result = await onChooseBackup();
      setBackupDate(result.createdAt || null);
      setRecoveryInput('');
      setPhase('restore-code');
    } catch (caught) {
      const message = caught instanceof Error ? caught.message : '';
      if (!/cancel/i.test(message)) setError(message || 'Could not open this backup');
    } finally {
      setBusy(false);
    }
  }

  const title = phase === 'create' ? 'Create your PIN'
    : phase === 'confirm' ? 'Confirm your PIN'
      : phase === 'recover' ? 'Recover your vault'
        : phase === 'restore-code' ? 'Unlock your backup'
          : phase === 'new-pin' ? 'Choose a new PIN'
            : phase === 'restore-new-pin' ? 'Create a new PIN'
              : phase === 'confirm-new' || phase === 'restore-confirm-new' ? 'Confirm new PIN'
                : 'Welcome back';
  const copy = phase === 'create'
    ? pendingAutofillSave ? 'Create a six-digit PIN, then review the login Android captured.' : 'Choose a six-digit PIN used only for Pocket Vault.'
    : phase === 'confirm'
      ? 'Enter the same PIN again to protect your vault.'
      : phase === 'recover'
        ? 'Enter the recovery code saved when you created your vault.'
        : phase === 'restore-code'
          ? `Enter the recovery code for the selected backup${backupDate ? ` from ${new Date(backupDate).toLocaleDateString()}` : ''}.`
          : phase === 'new-pin'
            ? 'Choose a replacement six-digit PIN.'
            : phase === 'restore-new-pin'
              ? 'Choose the six-digit PIN you will use on this phone.'
              : phase === 'restore-confirm-new'
                ? 'Enter your new PIN again to restore the vault.'
                : phase === 'confirm-new'
                  ? 'Enter your new PIN again.'
                  : pendingAutofillSave ? 'Unlock Pocket Vault to review and save the new login.' : 'Enter your Pocket Vault PIN to continue.';

  return (
    <main className="pin-screen">
      <div className="lock-orb orb-one" /><div className="lock-orb orb-two" />
      <section className="pin-card">
        <div className="brand-mark"><LockKeyhole size={29} strokeWidth={1.8} /></div>
        <p className="eyebrow">{phase === 'unlock' ? 'POCKET VAULT' : phase.startsWith('restore') ? 'RESTORE BACKUP' : 'SECURE SETUP'}</p>
        <h1>{title}</h1>
        <p className="pin-copy">{copy}</p>
        {phase === 'recover' || phase === 'restore-code' ? (
          <label className="recovery-input-label">Recovery code
            <input
              className="recovery-input"
              value={recoveryInput}
              onChange={(event) => { setRecoveryInput(event.target.value.toUpperCase()); setError(''); }}
              placeholder="PV-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX"
              autoCapitalize="characters"
              autoCorrect="off"
              spellCheck={false}
            />
          </label>
        ) : (
          <>
            <div className="pin-dots" aria-label={`${pin.length} of 6 digits entered`}>
              {Array.from({ length: 6 }, (_, index) => <span className={index < pin.length ? 'filled' : ''} key={index} />)}
            </div>
            <div className="pin-keypad" aria-label="PIN keypad">
              {[1,2,3,4,5,6,7,8,9].map((value) => <button type="button" onClick={() => digit(String(value))} key={value}>{value}</button>)}
              <span />
              <button type="button" onClick={() => digit('0')}>0</button>
              <button type="button" className="pin-delete" onClick={() => setPin((current) => current.slice(0, -1))} aria-label="Delete digit"><Delete size={21} /></button>
            </div>
          </>
        )}
        <div className="pin-error" role="alert">{error}</div>
        <button className="pin-continue" type="button" disabled={(phase === 'recover' || phase === 'restore-code' ? recoveryInput.replace(/[^A-Za-z0-9]/g, '').length !== 26 : pin.length !== 6) || busy} onClick={submit}>
          {busy ? 'Opening vault…' : phase === 'recover' || phase === 'restore-code' ? 'Continue' : phase === 'create' || phase === 'new-pin' || phase === 'restore-new-pin' ? 'Continue' : phase === 'confirm' ? 'Create encrypted vault' : phase === 'confirm-new' ? 'Reset PIN and unlock' : phase === 'restore-confirm-new' ? 'Restore vault' : 'Unlock vault'}
        </button>
        {phase === 'create' && <button className="restore-backup" type="button" onClick={selectBackup} disabled={busy}><Upload size={16} />{busy ? 'Opening files…' : 'Restore encrypted backup'}</button>}
        {phase === 'unlock' && <button className="forgot-pin" type="button" onClick={() => { setPhase('recover'); setPin(''); setError(''); }}>Forgot PIN?</button>}
        {phase === 'recover' && <button className="forgot-pin" type="button" onClick={() => { setPhase('unlock'); setRecoveryInput(''); setError(''); }}>Back to PIN</button>}
        {phase === 'restore-code' && <button className="forgot-pin" type="button" onClick={() => { setPhase('create'); setRecoveryInput(''); setError(''); }}>Back to setup</button>}
        <p className="pin-security"><ShieldCheck size={14} /> Your PIN is never stored. Your recovery code can set a new PIN.</p>
        {legacyVaultPresent && phase !== 'unlock' && <p className="legacy-note">Your previous device-auth vault remains stored locally as a backup.</p>}
      </section>
    </main>
  );
}

function RecoveryCodeSheet({ code, onClose }: { code: string; onClose: () => void }) {
  const [saved, setSaved] = useState(false);
  const [message, setMessage] = useState('');
  const [downloading, setDownloading] = useState(false);

  async function copyCode() {
    try {
      await vault.copySecret({ value: code, label: 'Recovery code', clearAfterSeconds: 60 });
      setMessage('Recovery code copied');
    } catch {
      setMessage('Could not copy the recovery code');
    }
  }

  async function downloadCode() {
    setDownloading(true);
    setMessage('');
    try {
      await vault.exportRecoveryCode({ code });
      setSaved(true);
      setMessage('Recovery file saved');
    } catch (caught) {
      const text = caught instanceof Error ? caught.message : '';
      if (!/cancel/i.test(text)) setMessage('Could not save the recovery file');
    } finally {
      setDownloading(false);
    }
  }

  return (
    <div className="recovery-backdrop">
      <section className="recovery-sheet" role="dialog" aria-modal="true" aria-labelledby="recovery-title">
        <div className="recovery-icon"><KeyRound size={27} /></div>
        <p className="eyebrow">SAVE THIS ONCE</p>
        <h2 id="recovery-title">Your recovery code</h2>
        <p className="recovery-copy">Use this code to reset your PIN if you forget it. Pocket Vault cannot show it again.</p>
        <code className="recovery-code">{code}</code>
        <div className="recovery-actions">
          <button type="button" onClick={downloadCode} disabled={downloading}><Download size={18} />{downloading ? 'Opening…' : 'Save text file'}</button>
          <button type="button" onClick={copyCode}><Copy size={18} />Copy</button>
        </div>
        <p className="local-save-note"><ShieldCheck size={14} /> In Android, choose Downloads or On this device to keep the file local.</p>
        {message && <p className="recovery-message" role="status">{message}</p>}
        <label className="recovery-confirm"><input type="checkbox" checked={saved} onChange={(event) => setSaved(event.target.checked)} /><span>I saved this recovery code somewhere safe.</span></label>
        <button className="recovery-done" type="button" disabled={!saved} onClick={onClose}>Continue to my vault</button>
      </section>
    </div>
  );
}

function BackupSheet({ lastBackup, autofillAvailable, autofillEnabled, onEnableAutofill, onExport, onClose }: {
  lastBackup: number | null;
  autofillAvailable: boolean;
  autofillEnabled: boolean;
  onEnableAutofill: () => Promise<void>;
  onExport: () => Promise<void>;
  onClose: () => void;
}) {
  const [exporting, setExporting] = useState(false);
  const [enabling, setEnabling] = useState(false);
  const [error, setError] = useState('');

  async function exportNow() {
    setExporting(true);
    setError('');
    try {
      await onExport();
    } catch (caught) {
      const message = caught instanceof Error ? caught.message : '';
      if (!/cancel/i.test(message)) setError(message || 'Could not save the backup');
    } finally {
      setExporting(false);
    }
  }

  async function configureAutofill() {
    setEnabling(true);
    setError('');
    try {
      await onEnableAutofill();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Could not open Android Autofill settings');
    } finally {
      setEnabling(false);
    }
  }

  return (
    <div className="sheet-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className="backup-sheet" role="dialog" aria-modal="true" aria-labelledby="backup-title">
        <div className="sheet-handle" />
        <div className="editor-header">
          <div><p className="eyebrow">VAULT SETTINGS</p><h2 id="backup-title">Pocket Vault settings</h2></div>
          <button type="button" className="icon-button" onClick={onClose} aria-label="Close backup settings"><X size={21} /></button>
        </div>
        <section className="autofill-card">
          <div className="autofill-heading"><div><KeyRound size={21} /></div><span><strong>Save logins from other apps</strong><small>Android asks before Pocket Vault receives anything.</small></span></div>
          {autofillAvailable ? (
            <button type="button" onClick={configureAutofill} disabled={enabling}>{autofillEnabled ? <Check size={17} /> : <Settings size={17} />}{enabling ? 'Opening…' : autofillEnabled ? 'Autofill enabled · Manage' : 'Enable Android Autofill'}</button>
          ) : <p>Requires Android 8 or newer.</p>}
        </section>
        <div className="settings-divider" />
        <p className="eyebrow backup-eyebrow">BACKUP &amp; TRANSFER</p>
        <h3 className="backup-title">Move to a new phone</h3>
        <div className="backup-illustration"><Download size={27} /></div>
        <p className="backup-copy">Create a portable encrypted copy of every saved login. The file can only be restored with your recovery code.</p>
        <button className="backup-export" type="button" onClick={exportNow} disabled={exporting}><Download size={18} />{exporting ? 'Opening files…' : 'Export encrypted backup'}</button>
        <p className="local-save-note"><ShieldCheck size={14} /> Choose Downloads or On this device to keep it local.</p>
        <div className="backup-status"><span>Last backup</span><strong>{lastBackup ? new Date(lastBackup).toLocaleString() : 'No backup created yet'}</strong></div>
        {error && <p className="form-error" role="alert">{error}</p>}
        <div className="backup-help"><Upload size={18} /><p>On the new phone, tap <strong>Restore encrypted backup</strong> before creating a PIN.</p></div>
        <p className="backup-warning">Store the backup file and recovery code separately. You need both to restore the vault.</p>
      </section>
    </div>
  );
}

function EntryEditor({ entry, captured, onClose, onSave, onDelete }: {
  entry: VaultEntry;
  captured?: boolean;
  onClose: () => void;
  onSave: (entry: VaultEntry) => Promise<void>;
  onDelete: (id: string) => Promise<void>;
}) {
  const [draft, setDraft] = useState(entry);
  const [visible, setVisible] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const existing = Boolean(entry.title);
  const update = (field: keyof VaultEntry, value: string | boolean) => setDraft((current) => ({ ...current, [field]: value }));

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError('');
    try {
      await onSave(draft);
    } catch {
      setError('Could not save this login. Please try again.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="sheet-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <form className="editor-sheet compact-editor" onSubmit={submit}>
        <div className="sheet-handle" />
        <div className="editor-header">
          <div><p className="eyebrow">{captured ? 'ANDROID AUTOFILL' : existing ? 'EDIT LOGIN' : 'NEW LOGIN'}</p><h2>{captured ? 'Review captured login' : existing ? draft.title : 'Add to your vault'}</h2></div>
          <button type="button" className="icon-button" onClick={onClose}><X size={21} /></button>
        </div>
        {captured && <p className="captured-note"><ShieldCheck size={16} />Android asked you before sending these details. Confirm them before saving.</p>}
        <label>Title<input autoFocus={!existing} value={draft.title} onChange={(event) => update('title', event.target.value)} placeholder="e.g. Google" required /></label>
        <label>Username or email<input value={draft.username} onChange={(event) => update('username', event.target.value)} placeholder="you@example.com" autoCapitalize="none" /></label>
        <label>Password
          <div className="password-input"><input type={visible ? 'text' : 'password'} value={draft.password} onChange={(event) => update('password', event.target.value)} placeholder="Enter password" required />
            <button type="button" onClick={() => setVisible(!visible)} aria-label="Show password">{visible ? <EyeOff size={19} /> : <Eye size={19} />}</button>
          </div>
        </label>
        <button className="generate" type="button" onClick={() => { update('password', generatePassword()); setVisible(true); }}><Sparkles size={17} /> Generate strong password</button>
        <label className="favorite-toggle"><span><Star size={19} /> Add to favorites</span><input type="checkbox" checked={draft.favorite} onChange={(event) => update('favorite', event.target.checked)} /></label>
        {error && <p className="form-error" role="alert">{error}</p>}
        <div className="editor-actions">
          {existing && <button className="delete-button" type="button" onClick={() => window.confirm(`Delete ${entry.title}? This cannot be undone.`) && onDelete(entry.id)}><Trash2 size={18} /></button>}
          <button className="save-button" disabled={saving || !draft.title.trim() || !draft.password} type="submit"><Pencil size={18} />{saving ? 'Saving…' : existing ? 'Save changes' : 'Save login'}</button>
        </div>
      </form>
    </div>
  );
}
