# Chatty Mic Test

Android TV / Google TV test app for proving that a third-party app can use the original Onn 4K Pro's built-in far-field microphones and keep listening in the background.

## v0.4

- Keeps the foreground microphone/device tests from earlier versions
- Runs a foreground microphone service that keeps working after pressing Home
- Uses Vosk entirely on-device for offline speech recognition
- Listens specifically for **Hey Chatty**
- Shows live/last recognized text, wake count, routed microphone, PCM peaks, and errors
- Does not send wake-word audio to any cloud service
- Bundles the small US English Vosk model in the GitHub Actions APK

## Build

GitHub Actions builds the debug APK automatically on pushes to `main`.

Go to **Actions → Build Chatty Mic Test APK → latest successful run → Artifacts** and download **Chatty-Mic-Test-v0.4**.

The APK inside the ZIP is `app-debug.apk`.

## v0.4 wake-word test

1. Install v0.4 over the previous Chatty Mic Test build.
2. Make sure the physical microphone switch on the Onn 4K Pro is ON.
3. Launch Chatty Mic Test and leave the microphone selection on **AUTO** initially.
4. Select **START LOCAL ‘HEY CHATTY’ TEST**.
5. Wait until the status shows that the offline model is ready and the recognizer is **LISTENING locally for ‘Hey Chatty’**.
6. Say **Hey Chatty** several times from different distances.
7. Watch **Wake phrase detections**, **Currently hearing**, and **LAST WAKE**.
8. Press Home, open another app if desired, say **Hey Chatty**, then return to Chatty and check whether the wake count increased.

The wake-word test is deliberately local-only. ChatGPT/API integration comes later, after wake-word reliability is proven.

## Previously proven on the original Onn 4K Pro

- Android exposes two built-in microphone inputs.
- Both inputs receive room audio.
- Flipping the Onn's physical microphone switch OFF drops captured PCM to zero, confirming the signal is from the box microphones rather than the handheld remote.
- Background microphone capture continues after pressing Home.

## EMEET / Nvidia Shield fallback

Chatty still lists all Android audio inputs, so a USB EMEET speakerphone can also be tested on the Onn or Nvidia Shield by selecting the corresponding USB audio input.
