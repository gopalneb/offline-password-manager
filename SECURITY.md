# Security policy

## Supported versions

Security fixes are applied to the latest version on the main branch. Older APKs are not supported.

## Reporting a vulnerability

Do not publish credential exposure, cryptographic flaws, or exploitable Autofill issues in a public issue.

Use GitHub's private vulnerability-reporting feature for this repository when it is enabled. If private reporting is unavailable, open a public issue containing no exploit details or sensitive information and ask the maintainer for a private contact channel.

Include:

- Affected version and Android version
- Device or emulator model
- Reproduction steps using test credentials only
- Expected and observed behavior
- Security impact
- Suggested mitigation, if known

Never include real passwords, PINs, recovery codes, backup files, keystores, or signing credentials.

## Security properties

- AES-256-GCM encryption for vault content
- Random master key wrapped independently for PIN and recovery
- PBKDF2-derived wrapping keys with random salts
- PIN attempt throttling
- No INTERNET permission
- Android backup and screenshots disabled
- Sensitive Android clipboard marking and timed clearing
- User-approved Android Autofill save flow
- Encrypted portable backups

## Scope limitations

The project has not received an independent security audit. It does not claim protection against a rooted device, compromised OS, malicious keyboard, screen overlay, memory inspection with full process control, or physical coercion.

## Disclosure expectations

Please allow reasonable time to investigate and prepare a fix before public disclosure. The maintainer will acknowledge a valid report, communicate status when possible, and credit the reporter if requested.
