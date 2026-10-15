# DREIDREI Player — Android APK project

**Made by DREIDREI**. This wraps the DREIDREI Player web app in a native Android WebView shell so it can be built into an installable APK.

## Build an APK without a computer (GitHub Actions)
1. Create a GitHub repository and upload the contents of this folder to its root.
2. Open the repository's **Actions** tab and select **Build DREIDREI Player APK**.
3. Press **Run workflow** (or push a commit to `main`/`master` to trigger a build).
4. Open the completed workflow run and download the artifact named **DREIDREI-Player-debug-APK**.
5. Extract the artifact ZIP to get `app-debug.apk`, then move it to your Android phone and install it. Android may ask you to allow installs from the app you used to open the file; only enable this for a source you trust.

## Build with Android Studio
Open this folder as a project in Android Studio with JDK 17 and Android SDK 35 installed, then choose **Build → Build APK(s)**. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Features
- MP3 and supported audio/video file import using Android's file picker
- Favorites, playlists, play counts, smart shuffle, queue and play-next actions
- Local IndexedDB storage inside the app's WebView
- Responsive interface and media session metadata support where supported by Android WebView

## Notes
- This project builds a **debug APK**, not a Play Store-signed release APK.
- Background playback behavior depends on Android version, WebView and power-management settings; it is not guaranteed on every device.
- The app imports files you choose. It does not download music or bypass streaming-service restrictions.
