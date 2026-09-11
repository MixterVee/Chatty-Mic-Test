# Chatty Mic Test

Tiny Android TV / Google TV microphone test app for checking whether a third-party app can access the built-in microphone on the original Onn 4K Pro.

## v0.1

- Requests microphone permission
- Starts a live microphone capture using `VOICE_RECOGNITION`
- Shows a live level meter
- Shows Android-reported input device type, product name, and ID
- Built for sideloading on Android TV / Google TV

## Build

GitHub Actions builds the debug APK automatically on pushes to `main`.

Go to **Actions → Build Chatty Mic Test APK → latest successful run → Artifacts** and download **Chatty-Mic-Test-v0.1**.

The APK inside the ZIP is `app-debug.apk`.

## Test procedure

1. Sideload `app-debug.apk` onto the Onn 4K Pro.
2. Make sure the physical microphone switch on the Onn is enabled.
3. Launch **Chatty Mic Test**.
4. Allow microphone access when prompted.
5. Talk toward the Onn box from several feet away.
6. Watch the live level percentage and bar.
7. Note the reported active input device and product name.

If the level responds to your voice, the built-in microphone is exposed to an ordinary Android app while the app is in the foreground.

The next test will be whether capture can continue from a foreground service after leaving the app.
