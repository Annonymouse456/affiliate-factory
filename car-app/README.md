# Car display app

Android app for a head unit: live picture from a USB (UVC) dashcam, speed from GPS, a tappable
speed-limit sign and a planned-path overlay. It exists because the browser on the head unit cannot
see the USB dashcam, so the web version in `../car/` cannot show a live picture.

## Build

Needs JDK 17+, Gradle 8.9+ and the Android SDK (platform 34, build-tools 34).

```
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk ../car/app.apk
```

Without `CAR_KEYSTORE` and `CAR_KEYSTORE_PASS` set, the APK is signed with the local debug key.
An APK signed with a different key will not install over an existing one; uninstall the old app first.

## On-screen report

The "ข้อมูล USB" key lists every USB device the app can see, whether each is a standard (UVC)
camera, and the last steps of opening the camera. "ลองกล้อง" asks the next USB device for a picture.
A crash is saved and shown at the next start.
