# AuthRec: notes for Claude sessions

Android app (Kotlin, no AndroidX) that records log video straight from the camera's RAW stream:
the GPU does demosaic → colour → log curve → LUT, the hardware encoder writes HEVC/H.264. The
ISP is used only for metering (AE/AWB/AF decisions, colour matrix, lens-shading map). Owner tests
on a **Xiaomi 14** (houji, SM8650, Android 16 / HyperOS 3); a friend tests a **Xiaomi 15 Ultra**
via the GitHub release + in-app "Send diagnostics…". Current state, backlog and what's verified:
**docs/HANDOFF.md**. Owner feedback, diagnostics and samples land in **feedback/**.

## Build, install, release

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # system java is absent
./gradlew assembleDebug                     # app/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/platform-tools/adb install -r -g app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease                   # signed via keystore.properties (git-ignored)
```
- Use the SDK's adb (`~/Library/Android/sdk/platform-tools/adb`); a Homebrew adb also exists and
  two adb versions fighting causes "unauthorized".
- AGP 9.4 (built-in Kotlin), Gradle 9.8, compile/target SDK 36, minSdk 31.
- Signing key: `~/Desktop/Android-Projects/AuthRec-signing/` (outside the repo, never commit it).
  The owner's phone runs the **debug** build; release and debug signatures differ, so installing
  one over the other needs an uninstall (which wipes settings/looks).
- Release: bump `versionCode`/`versionName` in `app/build.gradle.kts`, `assembleRelease`, push
  main, copy the APK to a file named `AuthRec-X.Y.Z.apk` (a `file#label` argument only sets the
  display label; the download stays `app-release.apk`), then
  `gh release create vX.Y.Z <dir>/AuthRec-X.Y.Z.apk --prerelease --target main`
  (repo: github.com/lahaan/AuthRec, public). Publishing is outward-facing: confirm with the owner.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Code map (app/src/main/java/com/authrec)

| File | Role |
|---|---|
| `CameraActivity.kt` | The whole UI (plain Views, landscape), settings/prefs, lens selection & fallback, camera failure recovery (`onCameraFailure`: retries, session layouts, fallback to 1x) + watchdog, AF modes (tap = one-shot AF + spot watch, contrast-AF hookup), AE: Priority loop, exposure routing, heat guard, recording start/stop (4K warm-up), looks list, adb hooks (`handleCommands`) |
| `camera/RawCamera.kt` | Camera2 session (all device/session work on the camera thread): RAW_SENSOR stream + tiny YUV "metering" stream, session layouts 0–2 (`variant`), request building (AE/AF/AWB/zoom routing/regions), per-frame `FrameMeta` with colour-metadata fallbacks, failure reporting, reference-shot capture |
| `camera/LensProbe.kt` | Finds every RAW-capable lens (listed ids, hidden ids 0–31, zoom routes, physical sub-cameras), test-streams each with HAL-recovery waits, keeps listed cameras, caches per firmware (`VERSION`), labels 0.6x/1x/2.6x |
| `EventLog.kt` | Persistent event log (`files/events.log`: opens, layouts, failures, retries, scans, recordings, heat, crashes); part of Send diagnostics |
| `ExposureSlider.kt` | The vertical EV slider (relative drag, double-tap = 0, amber where it's digital gain) |
| `gl/Renderer.kt` | GL thread: RAW upload, 3 compute passes, preview draw, encoder-surface draw (2nd shared EGL context, 10-bit config), auto gain, sharpness metric for contrast AF, stall stats |
| `gl/PipelineShaders.kt` | GLSL: prep (black/shading/WB/defect pixels) → develop (MHC demosaic or superpixel, matrix, log) → finish (chroma NR, clean log out, then for the view: tone balance, LUT input conversion, tetrahedral LUT, saturation/vibrance) + display (peaking) |
| `gl/GlUtil.kt` | EGL core (main + encoder contexts), GL helpers |
| `record/Recorder.kt` | MediaCodec video (surface input) + AAC audio on the camera clock + MediaMuxer → MediaStore `Movies/AuthRec` |
| `SoftwareAf.kt` | Contrast-detect AF (coarse/fine sweeps, warm-up step, parabola fit, continuous monitor) for lenses whose ISP AF doesn't work for us |
| `color/*` | `LogProfile` (Apple Log, S-Log3, LogC3 encode/decode), `ColorMath` (primaries → matrices), `Look`/`Looks` (grading params, film-style presets, bake to LUT), `CubeLut` (.cube parse/write, CPU tetra/trilinear apply), `ColorCalibration` (DNG forward-matrix route) |
| `LookEditorActivity.kt` | Look editor on a captured log frame; saves `*.look.json` + exports `.cube` to `Download/AuthRec` |
| `Diagnostics.kt` | Text report (device, every camera id, lens scan, prefs, own logcat) → share sheet |
| `bench/*` | Capability bench (RAW fps, GPU timing, encoder probes); opened from the lens menu |

## Pipeline facts worth knowing before changing things

- **RAW is ~2.2 EV underexposed on the main camera by Xiaomi's AE** (protects highlights; the ISP
  lifts it later). Telephoto/front are only ~0.6 EV under. So the pre-log gain is **automatic per
  lens**: `log2(ISP-rendered brightness / RAW brightness) + AUTO_BIAS_EV (0.3)`, smoothed, and it
  only *learns* at EV 0 in AE Auto (ISP tone mapping partly undoes EV compensation; a gain chasing
  it cancels the user's EV). Log curves hold the sensor clip point even at ~+2.5 EV gain.
- **The user's EV** is one value (`exposureEv`): AE Auto/Locked → AE compensation (sensor),
  residual below one AE step → digital gain; AE Priority → shifts the loop's target; Manual →
  digital gain.
- **Colour**: ISP per-frame `COLOR_CORRECTION_TRANSFORM` + gains. Verified vs ISP JPEGs (hue ~2.5–3°
  off). DNG forward-matrix route exists as fallback for lenses without ISP colour data.
- **Every session needs the small YUV metering stream**: Xiaomi's front and hidden telephoto run no
  AE/AWB (placeholder metadata: fixed ISO 150 1/50, no WB, no shading map) with RAW alone.
- **Lens routing on the Xiaomi 14**: main = id 0; ultrawide = hidden id 3 (opened directly: through
  logical 0 it stalls in low light when the logical camera switches sensors); telephoto = hidden
  id 4 direct (ISP AF fake, so contrast AF) **or** logical id 5 at `CONTROL_ZOOM_RATIO` 2.6 with RAW
  routed to physical 4 (native PDAF/laser continuous AF works there; preferred, with automatic
  fallback to the direct route). On zoom routes the HAL needs one stream on the logical camera
  (RAW-only physical → "Broken pipe" configure failure) and **ignores AF triggers and AF regions**:
  taps there use our contrast AF. Xiaomi gates aux lenses for whitelisted apps
  (`vendor.camera.aux.packagelist`); MotionCam also uses vendor tags / session op modes.
- **Contrast AF timing**: the HAL reports a new focus distance immediately but the lens needs
  ~4–5 frames; measuring early attributes the previous position's sharpness to the new one. Fine
  pass waits 6 frames, every pass starts with a discarded warm-up step.
- Frame timestamps are boottime (`SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`); audio is timestamped on
  the same clock and video pts = sensor ts − first frame ts.
- Camera can only be opened while the activity is resumed (HyperOS refuses "background" opens).
- Front camera rotation: `(sensorOrientation + displayDeg) % 360` (back: minus). App is landscape-only.
- Changing AF mode per tap (video ⇄ picture) stalls Xiaomi's pipeline for a moment: avoid.
  CONTINUOUS_VIDEO ⇄ AUTO costs at most one frame (measured).
- **Xiaomi's continuous AF ignores AF regions and AF_TRIGGER_CANCEL** (X14 main: focus didn't move
  for near or far taps). So a tap = AUTO + region + trigger (fast PDAF one-shot), and the activity's
  spot watch re-triggers it when the spot's sharpness stays below 60 % of its locked value for
  15 frames ("AF: Auto" stays the label); long-press = the same without the watch ("AF: Locked");
  double-tap = back to CONTINUOUS_VIDEO.
- **Heat: HyperOS PowerKeeper force-stops even the foreground app at ~48 °C battery**
  (`mAllowedKillBatteryTempThreshhold is 48`; Android's thermal status still reads 0). The app
  watches ACTION_BATTERY_CHANGED: banner from 43 °C, warning from 45 °C, recording stops (file
  saved) and won't start at 47 °C.
- The preview uses the superpixel path between recordings (≈4 ms vs ≈15–25 ms a frame); 4K
  recording first runs 1 s at full resolution (jumping straight in dropped frames while the GPU
  clocked up). The finish pass only writes the full-size log image when something uses it.
- **Tone balance** ("Balance" button, Adjust → Highlights/Shadows, prefs `toneHi`/`toneLo`):
  a luminance-based curve in stops around middle grey (highlights above +1 stop compressed, shadows
  below −1 stop lifted, half-stop soft knees) applied before the look/LUT. It answers Xiaomi's AE
  exposing for the sky (the ISP then tone-maps locally; our single global gain leaves the sky white
  in the view while the log keeps it). View and baked recordings only; the clean log never gets it.
- `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` can name the main sensor while a physical stream of
  another sensor still delivers (X14 `5/4`), so it's only a hint.
- **Xiaomi 15 Ultra (HyperOS 2)**: hidden ids opened directly fail at configure with
  `Function not implemented (-38)`; a RAW-only physical stream of a non-active lens on logical 0
  crashed its camera HAL (every later call: `unknown device`). Zoom routes (logical 0 +
  CONTROL_ZOOM_RATIO, physical RAW + logical YUV) are the expected way in; unverified until the
  friend's next diagnostics.

## Conventions

- Match the existing style: comments explain *why*, KDoc on non-obvious functions, no AndroidX
  (platform `Activity`, `startActivityForResult`), no new dependencies without a reason.
- Settings persist in SharedPreferences `authrec`; lens cache in `lenses` (bump
  `LensProbe.VERSION` when probing logic changes so devices rescan).
- Anything that can fail on an unknown phone (lens routes, metadata, session config) must fail
  soft: catch, log under tag `AuthRec`, show a message or fall back. Never crash on HAL quirks.
- UI strings are short; Simple mode stays minimal (look, adjust, exposure, 4K/2K, record).

## Testing on the phone

- adb hooks: `adb shell am start -n com.authrec/.CameraActivity --es cmd rec --es codec HEVC_10 …`
  (full list in the KDoc of `CameraActivity.handleCommands`; `lens=5/4`, `tap=0.5,0.5`, `ev=-1.0`,
  `clean=3`, `afverbose=true` (`--ez`), `cmd=dumpcams|refshot|edit|rescan|failcam`, `layout=0..2`,
  `fakeheat=47.5`, `lutinput=SLOG3`, `fullpreview=true`).
  **Every `am start` pauses and resumes the activity, i.e. closes and reopens the camera**, so
  state that lives in the session (AF, a recording) is reset by the next command; hooks that must
  act on a running session post themselves (`failcam` after 2 s, `fakeheatdelay`). For taps use
  real input instead: `adb shell input tap X Y` (the 4:3 image spans x 535–2135 on the X14),
  long-press `adb shell input swipe X Y X Y 900`.
- Event log: `adb shell run-as com.authrec cat files/events.log` (debug build).
- **Mind the heat while testing**: the app runs the camera whenever it's in front. Go back to the
  home screen (or YouTube) between tests and watch `adb shell dumpsys battery | grep temperature`
  (tenths of °C); above ~44 °C let it cool, at 48 °C PowerKeeper starts killing apps (YouTube too).
- `tools/rectest.sh <secs> <tag> [extras]` records, pulls, reports drops (needs ffmpeg).
- `tools/refshot/` RAW+ISP JPEG reference and the Python pipeline replica for colour/exposure work.
- Screenshots: `adb exec-out screencap -p > shot.png` (2670×1200 landscape). UI element bounds:
  `adb shell uiautomator dump` then read `/sdcard/ui.xml`; `adb shell input tap X Y` to press.
- The phone must be awake and unlocked; if the owner is away, ask them to leave a YouTube video
  playing (or enable Developer options → Stay awake) and return to YouTube after testing.
- zsh: `set -- $var` doesn't word-split; use arrays/case statements in loops.
- Logs: `adb logcat -s 'AuthRec:*'` (quote the filter in zsh). `adb logcat -c` also clears the
  crash buffer; use `adb logcat -b all -c` deliberately and check `-b crash` before clearing.
