# Privacy policy

Effective date: 20 August 2026

Pocket Vault is designed to operate locally on the user's Android device.

## Data handled

The app may handle:

- Login titles
- Usernames or email addresses
- Passwords
- An app-specific PIN
- A recovery code
- Encrypted backup files
- Login details the user explicitly approves through Android Autofill

## Collection and sharing

Pocket Vault does not send this information to the developer or any third party. The app requests no INTERNET permission and includes no analytics, advertising, account system, or cloud backend.

## Storage

Saved logins are encrypted before being written to private app storage. The PIN and recovery code are not stored. Recovery-code text files and encrypted backup files are created only when the user requests them and are saved to a destination chosen through Android's document picker.

Android Autofill credentials are received only after the user accepts Android's Save prompt. They remain in process memory until the user unlocks Pocket Vault and either confirms or discards the entry.

## Clipboard

When the user copies a username or password, Android receives that value through the system clipboard. On supported Android versions, Pocket Vault marks the content as sensitive and attempts to clear unchanged copied content after 30 seconds. Android or another app may still expose or retain clipboard data.

## Retention and deletion

Vault data remains on the device until the user deletes individual entries or clears/uninstalls the app. Exported recovery and backup files are controlled by the user and are not deleted automatically.

## Backups

Android OS backup and device-transfer backup are disabled for Pocket Vault. Portable backups are manually exported, encrypted .pvault files.

## Security limitations

No software can guarantee absolute security. Rooted devices, compromised operating systems, malicious keyboards, screen overlays, or an attacker with complete device control may bypass protections.

## Contact

For privacy questions, open an issue at:

https://github.com/gopalneb/offline-password-manager/issues
