# BSCA SB Scan

BS Cargo Agency Ltd. shipping bill scanner for Android 10 and newer. CameraX
and bundled ML Kit run offline. A green outline marks a new container code.
A red outline marks a repeat. Both give haptic feedback. Count a repeat if it
belongs to a separate document, or ignore it if the same document was scanned
again. Each counted scan is recorded locally.

If the camera sees multiple stable numbers, Capture one counts only the number
nearest the center. Capture all counts each distinct visible number and queues
its CPA lookup. If any chosen number already appears in the batch, confirm or
ignore that repeat separately.

In Settings, save a CPA account for native form sign-in and automatic session
renewal. The password is encrypted using Android Keystore. Native sign-in
defaults to MD5 as a compatibility option. The example CPA request did not
match a plain MD5 of the example password. If needed, use full-screen CPA
sign-in with Save session, or enable the encoded-field option with CPA’s exact
form value. Pending lookups resume with connectivity and failures stay visible
in Docs. The lookup uses the export container slot rotation, exact import
rotation match, the import voyage digits plus one with an S suffix, and the
date from the vessel report’s ETD field.

Reports update in Downloads/BSCA SB Scan. Reports also offers Save Word copy,
Save Excel copy and Share. Pending scans are excluded until matched. Reports
start from the supplied company template. Existing rows and quantities are
preserved, with new quantities added to unambiguous matching rows. New rows
fill the template's blank rows and extend its table as needed. The attached
template contains real company records and is bundled under app/src/main/assets.

## GitHub Builds and Releases

Push the project contents to the repository root, including .github. Every
push builds, signs, and verifies both APKs. The workflow run's **Artifacts**
section includes **BSCA-SB-Scan-APKs** for 14 days. Every successful push or manual run also publishes a
**GitHub Release** with both APKs. Push releases list the commits in that
push, and manual releases list the selected commit. GitHub-hosted runners and Actions must be enabled for the repo.

For release APKs that can update previous installs reliably, set these
repository Actions secrets and reuse the same keystore across builds:

| Secret | Value |
| --- | --- |
| ANDROID_KEYSTORE_BASE64 | Base64-encoded binary release keystore |
| ANDROID_KEYSTORE_PASSWORD | Keystore password |
| ANDROID_KEY_ALIAS | Signing key alias |
| ANDROID_KEY_PASSWORD | Signing key password |

Without release signing secrets, the release build is signed with that runner’s
debug key for testing. Android only accepts in-place APK updates signed with the
same key. Export your records before uninstalling an older test build.
The APKs use the same application ID, so install one flavor at a time.

The APK and repository contain the supplied company template with real shipping
records. Use a private repository if those records are for internal use.

For a local build, use JDK 17 with Android SDK Platform 35 and Build Tools
35.0.0. Run `./gradlew :app:assembleDebug` on Linux/macOS or
`gradlew.bat :app:assembleDebug` on Windows. The APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.
