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

## How it is used on the head unit

The head unit's dashcam is a vendor USB storage device, not a camera, so its picture cannot be read.
"ซ้อนบนแอปกล้อง" starts `OverlayService`, which floats a card over the dashcam app's live view:

- GPS speed, a tappable limit sign and a tone when over the limit
- the track playing over Bluetooth with previous / play-pause / next (`NowPlaying` gathers it from
  media sessions, the Bluetooth stack's media service, broadcasts and media notifications)
- trip distance, moving time, top speed, heading, altitude and a rough 0–100 km/h time
- a settings panel to shape the path band, and to start with the head unit (`BootReceiver`)

Drag the speed number to move the card; tap it to shrink or expand the card.

## On-screen reports

"ข้อมูล USB" lists every USB device the app can see and whether each is a standard (UVC) camera.
"เพลงบลูทูธ" shows what each music source reported, the Bluetooth link state and the Bluetooth or
music packages installed. A crash is saved and shown at the next start.
