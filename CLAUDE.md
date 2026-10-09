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
| `CameraActivity.kt` | State and actions behind the screen (landscape): settings/prefs, the views both layouts share (preview + gestures, info text, EV slider, focus bar, priority limits), lens selection & fallback, camera failure recovery (`onCameraFailure`: retries, session layouts, fallback to 1x) + watchdog, AF modes (tap = one-shot AF + spot watch, contrast-AF hookup), AE: Priority loop, exposure routing, eDR / WB trim / view adjustments, eDR suggestion, heat guard, recording start/stop (4K warm-up), looks list, adb hooks (`handleCommands`) |
| `ui/GlassUi.kt` | Default layout: lens chips + big record button + flip (+ selfie mirror on the front camera) on the right, mode/look/eDR/Adjust/Settings on the left, Pro bar (exposure, ISO and shutter dials, WB, focus, fps), Adjust panel with the eDR curve, settings sheet, REC pill, hint banner |
| `ui/ClassicUi.kt` | The original layout (buttons in two columns, exposure bar), Settings → Layout → Classic; its ⚙ (top right) switches back to Glass and has the lens tools |
| `ui/Glass.kt`, `RecordButton`, `ValueDial`, `ToneCurveView`, `CameraUi` | Glass drawable, `SelectionLayout` (sliding accent pill) + `UiKit` (controls in either style), the record button, the ISO/shutter ruler, the eDR curve graph, the layout interface and `Stepper` |
| `camera/RawCamera.kt` | Camera2 session (all device/session work on the camera thread): RAW_SENSOR stream + tiny YUV "metering" stream, session layouts 0–2 (`variant`), request building (AE/AF/AWB/zoom routing/regions), per-frame `FrameMeta` with colour-metadata fallbacks, failure reporting, reference-shot capture |
| `camera/LensProbe.kt` | Finds every RAW-capable lens (listed ids, hidden ids 0–31, zoom routes, physical sub-cameras), test-streams each with HAL-recovery waits, keeps listed cameras and lenses the previous scan found, skips routes that took the camera service down, caches per firmware (`VERSION`), labels 0.6x/1x/2.6x |
| `EventLog.kt` | Persistent event log (`files/events.log`: opens, layouts, failures, retries, scans, recordings, heat, crashes); part of Send diagnostics |
| `ExposureSlider.kt` | The vertical EV slider (relative drag, double-tap = 0, amber where it's digital gain) |
| `gl/Renderer.kt` | GL thread: RAW upload, compute passes, preview draw (+ frame mask), encoder-surface draw (2nd shared EGL context, 10-bit config, crop for 16:9 etc., pre-roll), auto gain, sharpness metric for contrast AF (centre-weighted), baked view LUT, recording timing, debug timings |
| `gl/PipelineShaders.kt` | GLSL: prep (black/shading/WB/defect pixels) → develop (MHC demosaic or superpixel, matrix, log, view, temporal colour NR, in one pass), colour NR's change test (`coarseColour`, `colourChange`) + `viewCommon` (eDR, LUT input conversion, branch-free tetrahedral LUT, saturation/vibrance) + display (crop, peaking) |
| `gl/GlassBackdrop.kt` | The Glass layout's glass: each control's shape filled with the preview as clear glass (buttons) or frosted (panels), bent at the rim |
| `color/ViewLut.kt` | Bakes eDR + LUT input conversion + LUT + strength into one 33³ LUT on a background thread |
| `gl/GlUtil.kt` | EGL core (main + encoder contexts), GL helpers |
| `record/Recorder.kt` | MediaCodec video (surface input) + AAC audio on the camera clock + MediaMuxer → MediaStore `Movies/AuthRec` |
| `SoftwareAf.kt` | Contrast-detect AF (coarse/fine sweeps, warm-up step, parabola fit, continuous monitor) for lenses whose ISP AF doesn't work for us |
| `color/*` | `LogProfile` (Apple Log, S-Log3, LogC3 encode/decode), `ColorMath` (primaries → matrices), `Look`/`Looks` (grading params, film-style presets, bake to LUT), `CubeLut` (.cube parse/write, CPU tetra/trilinear apply), `ColorCalibration` (DNG forward-matrix route) |
| `LookEditorActivity.kt` | Look editor on a captured log frame; saves `*.look.json` + exports `.cube` to `Download/AuthRec` |
| `Diagnostics.kt` | Text report (device, every camera id, lens scan, prefs, own logcat) → share sheet |
| `bench/*` | Capability bench (RAW fps, GPU timing, encoder probes); opened from the lens menu (Glass: long-press a lens chip, or Settings → Lenses…) |

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
  pass waits 6 frames, every pass starts with a discarded warm-up step; the coarse pass's peak
  lags about one step nearer, so the fine window spans 2 coarse steps far / 1 near.
- **Contrast AF taps** (zoom routes, backup telephoto): a 12 % region whose sharpness is
  centre-weighted (Gaussian), the nearest clear peak wins over the strongest (a subject in front
  of busy background), and following it is patient and local-only (never a full sweep, which
  jumped back to the background). Taps used to get the 24 % centre-AF box.
- Frame timestamps are boottime (`SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`); audio is timestamped on
  the same clock and video pts = sensor ts − first frame ts.
- Camera can only be opened while the activity is resumed (HyperOS refuses "background" opens).
- Front camera rotation: `(sensorOrientation + displayDeg) % 360` (back: minus). App is landscape-only.
  That gives the true image (X14: a bottle's label reads correctly in preview and file); the
  selfie-style mirror is the `mirrorFront` button next to flip (preview, taps and recording).
- **Preview resampling**: when the image is drawn at nearly a whole-number scale (front camera:
  1632 px into 1600, ×1.02) bilinear sampling makes the noise's strength beat in a grid (~50 px);
  such scales use a quintic B-spline (`Renderer.beatsWithNoise`). Recordings are 1:1.
- A lens switch to another RAW size (X14 1x 4096 ⇄ 2.6x 4080) replaces the Renderer; the camera
  keeps delivering until it's closed after it, so GL work queued behind `release` is dropped
  (`released`): a frame that ran on the destroyed context crashed with EGL_BAD_CONTEXT.
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
- The preview uses the superpixel path between recordings (≈4 ms vs ≈15–25 ms a frame).
- **4K budget** (X14, `pipetiming`, glFinish between stages, so pessimistic): upload ~2, prep ~3.5,
  develop+view ~12 ms (one merged pass); Clean's pixel fix adds ~3 to prep (2K too: prep always
  runs on the full RAW), its colour NR ~2 to develop (2K: ~1) and ~2 for its change test. Was ~27 ms before 2026-10-08: a
  separate finish pass re-read a full-size RGBA16F image, and the tetrahedral LUT's six branches
  diverged across each GPU wave (now branch-free; ties broken with one strict comparison, or
  greys came out wrong). The view's
  eDR / LUT input conversion / strength are baked into one 33³ LUT on a thread (`ViewLut`)
  whenever they change; until it matches the settings the per-pixel path runs.
- **Colour NR** (Clean 2/3, "+ Colour"/"+ Colour+") is temporal and colour-only: each pixel's
  colour (channels minus their Rec.709 mean) is blended with a ping-pong history (EMA weight
  0.7/0.85, reset on size/target change) where nothing moved, judged three ways: per pixel, the
  3×3-smoothed RAW brightness against the noise profile; per 8×8 and 16×16 sensor window
  (`coarseColour` 4×4 blocks → `colourChange`), the window's colour ((R−G, B−G)/(R+2G+B), so
  exposure changes don't count) against its own running average, in units of its noise: profile ×
  that channel's WB × shading gain × a scale each window learns while still (the profiles were off
  ×0.2–×3 per lens on the X14: still scenes read as moving on the 0.6x, the 2.6x was blind); and
  frame-wide, the mean window z² over noise's 2 (camera moving) backs the blend off to 30 %.
  Brightness alone let a grey cable over an orange mat leave trails; 4×4 blocks against the
  previous frame still trailed at high ISO and washed out greens under street lights while moving
  (owner, 0.4.0). Numpy replica at X14 ISO 3200 noise (`feedback/samples/2026-10-09-ui-nr/`):
  trails 27× fainter, a dark pan over leaves kept 91 % of their colour (was 19 %), still-scene NR
  within 10 %. ~2 ms at 4K for the two passes. `nrdebug=true` logs the frame-wide measure (still:
  0–0.3) and the learned noise scale. It runs on
  the finished view (or on the log when recording log): done before the view, eDR/LUT toes
  turned the missing noise into crushed darks; split in linear light, luminance noise went into
  all three channels (+50 % grain). Recorded luma is untouched (encoder input is exact BT.709
  limited, `recdebug=bars`). X14 at ISO 3200: colour noise −55 to −69 % on 1x and the
  ultrawide. The files show *more* luma grain with it (+10 % at 150 Mbps, +31 % at 50 Mbps):
  with less colour noise to code, the encoder stops smoothing luma. The spatial chroma filter it
  replaced left blotches and darkened noisy shadows.
- **Recording pre-roll**: REC starts the encoder at once (frames discarded) and the clip begins
  1 s later (2K: 0.5 s) at a requested key frame (re-requested every 4 frames; the X14 encoder
  once ignored one). It absorbs the first-use allocation of the encoder's ~50 MB input buffers
  and the GPU clock-up, which stalled single frames 100–200 ms and cost 10–20 % of clips 1–15
  frames. Each recording logs `Recording timing:` (frames lost and when, slowest frame, slowest
  encoder hand-over, slowest pre-roll frame).
- **Frame** (`aspect`: 4:3, 16:9, 2:1, 2.39:1): the encoder gets a centre crop of the image
  (height a multiple of 16, e.g. 4096×2304); the preview darkens the rest and draws frame lines.
- **Glass backdrop** costs ~1.7 ms a preview frame (`glasstiming`), refreshed every third frame
  while recording. Buttons are clear glass sampling the preview image itself (a blurred copy hid
  the bending: owner, "not like real glass"): a 4 % magnification, and towards the rim what lies
  just beyond the edge pulled in (a glass drop's look), a thin specular line on the lit rim,
  darker over bright scenes. Panels holding text are frosted (quarter-size blur). The views add
  only a light tint and a hairline rim (`GlassDrawable`; the old body gradient and top gloss were
  "too strong"). Look chosen from mock-ups over a real preview frame (same folder). Its blur passes
  run before the window's render pass: drawing them in between made the tile GPU store and reload
  the whole screen (~2 ms).
- **Selection** in Glass: `SelectionLayout` draws the accent pill behind its (nested) buttons and
  slides it to a new choice (lens chips, every option row); the chosen button's `GlassDrawable` is
  `clear`. Panels fade/grow in (`UiKit.reveal`); hiding stays instant because state reads
  `visibility`. Record button: white ring, flat red core, clear glass between (`glassBox`).
  Only top-level controls get GL glass: buttons on a glass panel are tinted shapes on its frost
  (each opened a clear window through it). The settings sheet keeps its title fixed and fades the
  scrolling rows at its edges (a hard cut under the rounded rim looked clipped).
- **eDR** (was "Balance"; prefs `edrOn`/`edrHi`/`edrLo`, default −75/+30, migrated from 0.2.2's
  `toneHi`/`toneLo`): a luminance-based curve in stops around middle grey (highlights above +1 stop
  compressed, shadows below −1 stop lifted, half-stop soft knees) applied before the look/LUT. It
  answers Xiaomi's AE exposing for the sky (the ISP then tone-maps locally; our single global gain
  leaves the sky white in the view while the log keeps it). View and baked recordings only; the
  clean log never gets it. Simple: on/off; Pro: the curve graph in Adjust (`ToneCurveView` draws
  the shader's own function). Simple suggests it once ever (`edrHintShown`) when >4 % of the view
  stays blown for 3 s while the RAW still holds it (`Renderer.recoverableClipFraction`).
- **WB trim** (Warmth/Tint, prefs `wbWarm`/`wbTint`): channel gains multiplied into the ISP's WB
  gains in the prep pass, so the recorded log gets them too (like a camera's WB shift).
- **Recorder**: the file always starts on a key frame (leading frames dropped, a sync frame
  requested, both tracks shifted to start at it) and `BUFFER_FLAG_PARTIAL_FRAME` pieces are joined
  into one sample. Each recording logs `Encoder <name>: first outputs [...] , output buffers N KB`
  to the event log (X14: c2.qti.hevc.encoder, first key frame ~3.9 MB in 7.6 MB buffers).
  `recdebug=split|dropkey` exercises both paths; `recdebug=bars` draws colour bars into the recording.
- `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` can name the main sensor while a physical stream of
  another sensor still delivers (X14 `5/4`), so it's only a hint.
- **Xiaomi 15 Ultra (HyperOS 2)**: hidden ids opened directly fail at configure with
  `Function not implemented (-38)`. Zoom routes (logical 0 + CONTROL_ZOOM_RATIO, physical RAW +
  logical YUV) work for the 3x (`0/4 @3.01`, every scan) and the periscope (`0/5 @4.12`, first
  frame after 0.4–4.2 s, so scans used to miss it; zoom routes now get 6.5 s in the scan and 10 s
  in the watchdog). The ultrawide's route `0/3 @0.61` (like 0.2.1's RAW-only physical stream)
  takes the camera service down every time: `LensProbe` now remembers such routes per firmware
  (`crashedRoutes`) and never tries them again. A lens a rescan misses is kept from the previous
  scan of the same firmware. The first frame of every open logs the colour metadata it uses and,
  on physical routes, whether it came from the lens's own result.

## Conventions

- Match the existing style: comments explain *why*, KDoc on non-obvious functions, no AndroidX
  (platform `Activity`, `startActivityForResult`), no new dependencies without a reason.
- Settings persist in SharedPreferences `authrec`; lens cache in `lenses` (bump
  `LensProbe.VERSION` when probing logic changes so devices rescan).
- Anything that can fail on an unknown phone (lens routes, metadata, session config) must fail
  soft: catch, log under tag `AuthRec`, show a message or fall back. Never crash on HAL quirks.
- UI strings are short; Simple mode stays minimal (look, eDR, adjust, exposure, 4K/2K, record).
- Two layouts over the same state and actions (`ui/CameraUi`): Glass (default) and Classic. A
  new setting needs a place in both (Classic: a cycling button or a menu item) or it's
  unreachable for whoever picked the other layout. Glass sizes are in dp; Classic keeps its px.

## Testing on the phone

- adb hooks: `adb shell am start -n com.authrec/.CameraActivity --es cmd rec --es codec HEVC_10 …`
  (full list in the KDoc of `CameraActivity.handleCommands`; `lens=5/4`, `tap=0.5,0.5`, `ev=-1.0`,
  `clean=3`, `afverbose=true` (`--ez`), `cmd=dumpcams|refshot|edit|rescan|failcam`, `layout=0..2`,
  `fakeheat=47.5`, `lutinput=SLOG3`, `fullpreview=true`, `edr=true`, `tonehi=-75 tonelo=30`,
  `wbwarm/wbtint`, `ui=glass|classic`, `edrhint=reset|show`, `recdebug=split|dropkey|bars|off`,
  `aspect=16:9`, `glasstiming=true`, `pipetiming=true`, `bakedview=false`, `mirror=true`, `nrdebug=true`).
  **Every `am start` pauses and resumes the activity, i.e. closes and reopens the camera**, so
  state that lives in the session (AF, a recording) is reset by the next command; hooks that must
  act on a running session post themselves (`failcam` after 2 s, `fakeheatdelay`). For taps use
  real input instead: `adb shell input tap X Y` (the 4:3 image spans x 535–2135 on the X14),
  long-press `adb shell input swipe X Y X Y 900`. Glass control bounds: `uiautomator dump` (below).
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
