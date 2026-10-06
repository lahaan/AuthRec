# AuthRec

Android camera app that records log video straight from the sensor's RAW stream, with real-time
LUTs. The phone's ISP only supplies metering (exposure, white balance, colour matrix, lens
shading); demosaicing, colour, log encoding and LUTs all run on the GPU.

- Open gate 4096×3072 (or 2048×1536 superpixel) from 14-bit RAW
- Apple Log / S-Log3 / LogC3, HEVC 10-bit / HEVC 8-bit / H.264, up to 150 Mbps, AAC audio
- Live 3D LUTs (tetrahedral), built-in film-style looks, look editor that exports `.cube`
- Lens discovery (incl. lenses hidden from normal apps), native or contrast-detect AF, focus peaking
- Tap to focus and follow a spot, long-press to lock focus, double-tap for automatic focus
- AE: Auto / Priority (shutter & ISO limits) / Locked / Manual; optional colour noise reduction
- Imported LUTs are fed the log format they were built for (e.g. an S-Log3 LUT while recording LogC3)
- Warns when the phone gets hot and stops recording cleanly before the system would close the app
- Simple mode (look, tap to focus, exposure, record) and Pro mode

Developed and tested on a Xiaomi 14 (Android 16 / HyperOS 3). Needs Android 12+ and a camera with
RAW support. CameraAPI2 L3 access necessary & SoC w GPU powerful enough (ie 8gen2 & above preferred)

## Toknow

Currently EV, Exposure and AF is a bit iffy and needs work and so does the whole UI/UX. Right now in just alpha/poc stage. Unknown how well works on other devices.

## Installing (no computer needed)

1. On the phone, download `AuthRec-<version>.apk`.
2. Open it. Android will ask to allow installing apps from your browser or file manager: allow it.
3. Xiaomi phones may show a security scan warning for apps not from the store: choose to install anyway.
4. Open AuthRec, allow camera and microphone. The first launch spends a few seconds finding lenses.

### Sending a report

Tap the lens button (top right, e.g. "1x") → **Send diagnostics…** and share the file through any
chat app. It contains the phone model, what each camera reports, the lens scan and the app's log.

## Building

Open in Android Studio, or `./gradlew assembleDebug`. Release builds are signed when a
`keystore.properties` file (not in git) points at the signing key:

```
storeFile=/path/to/authrec-release.jks
storePassword=…
keyAlias=authrec
keyPassword=…
```

Useful adb hooks for testing are documented on `CameraActivity.handleCommands`.
