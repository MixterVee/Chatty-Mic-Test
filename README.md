# Chatty Mic Test

Tiny Android TV / Google TV microphone test app for checking which microphone inputs a third-party app can see and actually use on devices such as the original Onn 4K Pro and Nvidia Shield.

## v0.2

- Requests microphone permission
- Starts live microphone capture using `VOICE_RECOGNITION`
- Shows a live level meter and PCM peak
- Lists every Android-reported microphone input device
- Provides a button to test each detected input individually
- Includes an AUTO mode that lets Android choose the input
- Shows both the requested microphone and the actual routed microphone
- Reports whether Android accepted a preferred-device routing request
- Includes a Refresh button for plugging/unplugging USB audio devices such as an EMEET speakerphone
- Built for sideloading on Android TV / Google TV

## Build

GitHub Actions builds the debug APK automatically on pushes to `main`.

Go to **Actions → Build Chatty Mic Test APK → latest successful run → Artifacts** and download **Chatty-Mic-Test-v0.2**.

The APK inside the ZIP is `app-debug.apk`.

## Onn 4K Pro built-in microphone test

1. Sideload `app-debug.apk` onto the original Onn 4K Pro.
2. Make sure the physical microphone switch on the Onn is enabled.
3. Launch **Chatty Mic Test** and allow microphone access.
4. Start with **AUTO — Let Android choose the microphone**.
5. Talk toward the Onn box from several feet away and watch the live level meter.
6. Note the **Active/routed input** shown near the top.
7. If more than one input is listed, select each **TEST INPUT** button and repeat the test.

## EMEET USB speakerphone test

1. Connect the EMEET speakerphone to the Onn 4K Pro or Nvidia Shield by USB.
2. Launch Chatty Mic Test, or press **Refresh audio devices** if it is already open.
3. Look for a **USB audio device** or **USB headset / speakerphone** entry.
4. Select that input and talk toward the EMEET.
5. Check whether **Routing request accepted by Android** says **YES**.
6. More importantly, check whether **Active/routed input** matches the EMEET/USB device and whether the live meter responds.

Android may accept a preferred-device request but still route capture elsewhere, so the **Active/routed input** is the most important result.

If foreground capture works, the next experiment is a foreground microphone service to see whether Chatty can continue listening after pressing Home and while another TV app is in use.
