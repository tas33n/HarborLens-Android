# HarborLens Android prototype

Native Android app built with CameraX and bundled ML Kit Latin text recognition.
Camera frames stay on the device. Align one container number in the center of
the viewfinder. The app checks the ISO 6346 digit and requires two matching
frames. Each accepted scan represents one document. Confirm another document
explicitly if it uses a container already in the batch.

The Documents tab stores scans on this phone and allows reviewing and removing
them. CPA records are sample data. The sample identifier TCKU3877226 is assigned
a demo sailing. Other identifiers start pending and can be assigned to a sample
sailing manually. The Memo tab groups matched documents by sailing and saves a
demo CSV using the Android document picker.

## Build

Open this folder in Android Studio with Android SDK Platform 35, Android SDK
Build-Tools 35.0.0 and a JDK 17 or compatible Gradle JDK. The initial Gradle sync
requires access to Gradle distributions, Google Maven and Maven Central.

On Windows, from the project folder:

    .\gradlew.bat :app:assembleDebug

On macOS or Linux:

    ./gradlew :app:assembleDebug

Gradle outputs a debug-signed APK at app/build/outputs/apk/debug/app-debug.apk.
The app supports Android 6.0 (API 23) and newer.

## Build on GitHub

Push the contents of this folder to the repository root so that
`.github/workflows/android-apk.yml` and `gradlew` are both at the expected paths.
The **Build Android APK** workflow runs on every push. You can also run it
manually from the **Actions** tab. After a successful run, open its summary and
download **HarborLens-debug-apk** under **Artifacts**. Extract `app-debug.apk`
from the download to install it on an Android device.
