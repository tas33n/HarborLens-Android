# BSCA SB Scan

BS Cargo Agency Ltd. shipping bill scanner for Android 10 and newer. CameraX
and bundled ML Kit run offline. A green outline marks a new container code.
A red outline marks a repeat. Both give haptic feedback. Count a repeat if it
belongs to a separate document, or ignore it if the same document was scanned
again. Each counted scan is recorded locally.

In Settings, sign in on the CPA page, then tap Retry pending lookups. The app
uses the CPA session cookie and Android WebView can offer password autofill.
Expired sessions require signing in again. CPA lookups run with connectivity,
and failures stay visible in Docs. The lookup uses the export container slot
rotation, exact import rotation match, the import voyage's digits plus one
with an S suffix, and the date from the vessel report's ETD field.

Reports update in Downloads/BSCA SB Scan. Reports also offers Save Word copy,
Save Excel copy and Share. Pending scans are excluded until matched. Reports
start from the supplied company template. Existing rows and quantities are
preserved, with new quantities added to unambiguous matching rows. New rows
fill the template's blank rows and extend its table as needed. The attached
template contains real company records and is bundled under app/src/main/assets.

## Build

Push the project contents to the repository root, including .github. Each
push runs Build Android APK. Once it succeeds, download BSCA-SB-Scan-debug-apk
from the run's Artifacts and extract app-debug.apk.

For a local build, use JDK 17 with Android SDK Platform 35 and Build Tools
35.0.0. Run `./gradlew :app:assembleDebug` on Linux/macOS or
`gradlew.bat :app:assembleDebug` on Windows. The APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.
