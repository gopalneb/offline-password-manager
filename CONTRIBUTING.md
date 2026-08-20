# Contributing

## Development setup

Use Node.js 22, npm, JDK 21, and Android SDK API 36.

~~~bash
npm ci
npm run dev
~~~

## Before submitting a pull request

~~~bash
npm run lint
npm run build
npm run android:sync
cd android
./gradlew --no-daemon testDebugUnitTest assembleDebug
~~~

## Security-sensitive changes

Changes to cryptography, key derivation, Autofill capture, backup format, clipboard handling, Android manifest permissions, or release signing require:

- A clear threat-model explanation
- Tests for failure and cancellation paths
- No logging of passwords, PINs, recovery codes, keys, or decrypted vault data
- Backward-compatibility notes for existing vaults and backups

Do not add analytics, advertising, network permissions, cloud services, or Accessibility access without an explicit project decision and matching privacy documentation.

## Pull requests

- Keep changes focused.
- Explain user-visible behavior and security impact.
- Add or update documentation.
- Use fake credentials in tests and screenshots.
- Do not commit node_modules, build outputs, local SDK paths, keystores, or secrets.

## Commit messages

Use short imperative summaries, for example:

~~~text
Add encrypted backup restore flow
Fix browser clipboard fallback
~~~
