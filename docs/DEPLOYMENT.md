# Deployment guide

This guide covers local development, debug builds, signed Android App Bundles, and Google Play submission.

## Prerequisites

- Node.js 22 and npm
- JDK 21
- Android Studio or Android command-line tools
- Android SDK Platform 36 and Build Tools 36
- A Google Play Console developer account for store publishing

Confirm the tools:

~~~bash
node --version
npm --version
java -version
~~~

Set ANDROID_HOME to the local Android SDK when it is not already configured.

## Install and verify

~~~bash
npm ci
npm run lint
npm run build
npm run android:sync
cd android
./gradlew --no-daemon testDebugUnitTest assembleDebug
~~~

The debug APK is created at:

~~~text
android/app/build/outputs/apk/debug/app-debug.apk
~~~

## Browser preview

~~~bash
npm run dev
~~~

Open http://127.0.0.1:5173/. Browser storage is intentionally temporary and does not reproduce Android's native storage, Autofill service, or document picker.

## Test on Android

1. Open the android directory in Android Studio.
2. Start an Android 8+ emulator or connect a phone with USB debugging.
3. Run the app configuration.
4. Test PIN creation, recovery-code export, login CRUD, copy actions, lock behavior, backup export, and clean-install restore.
5. Enable Pocket Vault under Android's **Passwords, passkeys & autofill** settings.
6. Submit a test login form in a compatible app or browser and verify Android's Save prompt.

Samsung and Honor devices should also be included in release testing because those vendors may customize lifecycle and Autofill behavior.

## Release versioning

Before every Play Store upload:

1. Increase versionCode in android/app/build.gradle. Google Play rejects a previously used version code.
2. Update versionName in android/app/build.gradle.
3. Update version in package.json and package-lock.json.
4. Add the release notes to CHANGELOG.md.
5. Run every quality check again.

## Create an upload key

Create the key outside the repository:

~~~bash
keytool -genkeypair -v \
  -keystore /secure/location/pocket-vault-upload.jks \
  -alias pocket-vault-upload \
  -keyalg RSA -keysize 4096 -validity 10000
~~~

Back up the keystore and passwords securely. Never commit them. Use a separate upload key from the Play App Signing key when possible.

## Configure release signing

Place these properties in the user-level Gradle file at ~/.gradle/gradle.properties, not in this repository:

~~~properties
PV_RELEASE_STORE_FILE=/absolute/path/to/pocket-vault-upload.jks
PV_RELEASE_STORE_PASSWORD=replace-with-store-password
PV_RELEASE_KEY_ALIAS=pocket-vault-upload
PV_RELEASE_KEY_PASSWORD=replace-with-key-password
~~~

The Android build uses these values only when all four are present.

## Build a signed App Bundle

~~~bash
npm ci
npm run android:sync
cd android
./gradlew --no-daemon clean bundleRelease
~~~

Output:

~~~text
android/app/build/outputs/bundle/release/app-release.aab
~~~

Verify the signing configuration:

~~~bash
./gradlew signingReport
~~~

Do not upload a debug APK to Google Play.

## Google Play Console

1. Create the app in Play Console using package name in.pocketvault.local. Package names are permanent after publication.
2. Enroll in Play App Signing.
3. Complete the store listing, content rating, target audience, app access, ads, and policy declarations.
4. Host PRIVACY.md as a public privacy-policy page and provide that URL.
5. Complete the Data safety form. Review the final source and dependencies rather than copying assumptions from this guide.
6. Upload app-release.aab to the internal testing track first.
7. Test installation, upgrade, recovery, encrypted backup/restore, clipboard clearing, and Autofill on real devices.
8. Promote through closed testing and production only after the test results are acceptable.

At the time this guide was written, new Play apps use Android App Bundles and Play App Signing. Play requirements change; verify the current official documentation before each release:

- https://developer.android.com/studio/publish/app-signing
- https://developer.android.com/studio/publish/upload-bundle
- https://support.google.com/googleplay/android-developer/answer/9859152
- https://support.google.com/googleplay/android-developer/answer/10787469

## Suggested Data safety review

The current source requests no INTERNET permission and contains no analytics or advertising SDK. Vault, PIN-recovery, clipboard, and Autofill data are handled locally. This is a technical observation, not a substitute for reviewing the Play definitions and the exact production artifact.

## Release checklist

- [ ] Version code and name updated
- [ ] Changelog updated
- [ ] npm ci succeeds
- [ ] Lint and web build pass
- [ ] Android unit tests and release bundle build pass
- [ ] Release bundle is signed with the upload key
- [ ] APK/AAB manifest contains no unexpected permissions
- [ ] Fresh install and upgrade tested
- [ ] Recovery code and wrong-code behavior tested
- [ ] Backup export and clean-install restore tested
- [ ] Autofill tested in at least one app and one browser
- [ ] Samsung and Honor smoke tests completed
- [ ] Privacy policy and Data safety answers reviewed
- [ ] Internal testing release accepted before production
