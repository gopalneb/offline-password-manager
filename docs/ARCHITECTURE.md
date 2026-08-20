# Architecture

## Overview

Pocket Vault is a Capacitor Android application with a React UI and a native Java security layer.

~~~text
React UI
  |
  | Capacitor NativeVault calls
  v
NativeVaultPlugin
  |-- PIN and recovery key derivation
  |-- AES-GCM vault encryption
  |-- encrypted backup import/export
  |-- sensitive clipboard handling
  |
  +-- internal app storage

Android Autofill framework
  |
  v
PocketVaultAutofillService
  |
  +-- in-memory AutofillCaptureStore
          |
          v
      PIN-gated review in React UI
~~~

## Web layer

The src directory contains the UI, entry validation, PIN flow, recovery flow, backup settings, and a browser-only adapter used for preview testing.

The browser adapter is not the production security boundary. Refreshing the preview can reset its PIN and state.

## Android vault layer

NativeVaultPlugin.java owns cryptographic operations and Android integrations.

- A random 256-bit master key encrypts vault JSON with AES-GCM.
- PBKDF2 derives independent wrapping keys from the six-digit PIN and recovery code.
- Salt, KDF identifier, and wrapped keys are stored in private SharedPreferences.
- Encrypted vault bytes are stored in private internal app storage.
- The unwrapped master key exists only for the active process session and is discarded when the vault locks.
- Incorrect PIN attempts are rate-limited.

## Backup format

The .pvault document is JSON containing format/version metadata, KDF settings, recovery salt, the recovery-wrapped master key, and AES-GCM encrypted vault bytes. It contains no plaintext login values and no recovery code.

Restore is available before a new vault is configured. The recovery code unwraps the imported master key, validates the encrypted vault, and a new local PIN wrapper is created.

## Autofill

PocketVaultAutofillService declares Android's BIND_AUTOFILL_SERVICE permission. It inspects only Autofill structures supplied by Android, returns SaveInfo for detected password forms, and receives values only after the user accepts the system Save prompt.

The captured login is held in AutofillCaptureStore memory and is not written to a temporary plaintext file. Pocket Vault must be unlocked before the React layer can read and review it. Closing the editor discards the pending capture.

## Trust boundaries

- The React UI does not implement production cryptography on Android.
- Capacitor calls cross from the WebView into native code.
- Android's document picker chooses recovery and backup destinations.
- Android's Autofill framework controls form access and save consent.
- Rooted devices, compromised operating systems, malicious keyboards, and processes with full device control are outside the current threat model.
