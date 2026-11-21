# Android app

The app targets Android 16 (API 36), supports Android 5.0+ (API 21), and uses lifecycle-bound CameraX. Online captures are first written to internal app storage and a SQLite outbox. The app makes one immediate prediction attempt for each capture with a usable network. If that attempt or its Firestore write fails, the image remains queued and is not retried while the UAV continues its inspection. At inspection end, the app makes one batch pass over that inspection's deferred images, shows a processed/pending summary, and exports any remaining images for web upload.

The separate offline batch mode continues to capture and review images without prediction, then exports them to `Pictures/SmartRiceCare/<folder>` for selection in the web upload form. It needs the instructor web workflow to associate and process those images later.

## Local configuration

Install Android SDK Platform 36 and use JDK 17. Create `Android/local.properties` with:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
GEO_MAP_API_KEY=your_maps_key
FLASK_SERVER_URL=https://your-prediction-service
```

Copy the Firebase project's `google-services.json` into `Android/app/` for sign-in, Firestore, and live field workflows. This file is intentionally not committed. Without it, the sign-in screen provides the offline capture entry point; maps and cloud features need their real project credentials.

Build a debug APK with:

```sh
cd Android
./gradlew assembleDebug
```

The APK is written to `Android/app/build/outputs/apk/debug/app-debug.apk`.
