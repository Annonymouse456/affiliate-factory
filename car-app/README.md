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

## Voice co-pilot

"ถาม AI" on the floating card records a spoken question, and the answer is read back.

- `Recorder` captures the question from the head unit's microphone and stops when the speaker goes quiet
- `Speech` turns Thai speech into text and the answer into speech through the OpenAI audio API
- `ClaudeClient` asks Claude (`claude-opus-5-5`) through the Messages API with the car's live readings, and
  runs the tool loop: web search on Anthropic's side, plus `nearby_places`, `look_at_road`,
  `set_speed_limit`, `set_path_overlay` and `media_control` in the app
- `ScreenGrab` takes one frame of the screen for `look_at_road`, after the driver allows screen capture
- `Assistant` ties these together and keeps running totals of requests and tokens

Keys are typed or pasted under "ตั้งค่า AI" and stored in the app's private preferences on the head
unit. They are never in this repository or in the APK. Speech, the screen frame and the car's position
are sent to Anthropic and OpenAI only when a question is asked, or when the optional automatic road
briefing is on.

`ClaudeClient` uses plain HTTPS rather than the Anthropic Java SDK so that the same code can be run
on the desktop JVM against a scripted server and behave identically on Android 8.1.

## On-screen reports

"ข้อมูล USB" lists every USB device the app can see and whether each is a standard (UVC) camera.
"เพลงบลูทูธ" shows what each music source reported, the Bluetooth link state and the Bluetooth or
music packages installed. A crash is saved and shown at the next start.
