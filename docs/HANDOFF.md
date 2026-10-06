# Handoff: state as of 0.2.2 (2026-10-06)

How it got here: built in one long session from a capability bench → live RAW preview → recording
→ features (0.2.0), then field tests on the owner's Xiaomi 14 and a friend's Xiaomi 15 Ultra
(0.2.1: diagnosable lens probe), then 0.2.2: robustness for the 15 Ultra and the owner's feedback.
Architecture and device quirks: **CLAUDE.md**. Feedback, diagnostics, samples: **feedback/**
(`notes.md` has every reported item, its diagnosis and status).

## Working and verified on the Xiaomi 14

- Open gate 4096×3072 from RAW_SENSOR (14-bit) at 24/25/30 fps; HEVC Main10 genuinely 10-bit;
  HEVC 8-bit and H.264 High; 150 Mbps; AAC stereo on the camera clock. Superpixel 2048×1536.
- Apple Log / S-Log3 / LogC3; built-in looks, user looks, imported .cube (tetrahedral); look editor.
- Lenses: 0.6x (hidden id 3), 1x (0), 2.6x (logical 5 → physical 4 at zoom 2.58, native AF;
  backup = direct id 4 with contrast AF), Front (1). 0.2.2's scan finds the same five in ~4 s.
- Colour vs ISP JPEG within ~2.5–3° hue; auto gain matches ISP midtone brightness.
- **0.2.2, tested this session:**
  - Lens scan: same result as before; a scan interrupted by leaving the app is abandoned and
    restarted cleanly; an earlier PowerKeeper kill mid-scan was counted as strike 1 (not skipped).
  - Camera failure recovery: simulated failures walk the whole ladder (retry with backoff → session
    layout 1 → layout 2 → streaming again, failure count reset). Layouts 0 and 2 stream on 1x and
    Front; 8 rapid lens switches in 3.5 s: no crash, last one streams.
  - Heat guard: banner from 43 °C, warning from 45 °C; with a faked 47.3 °C mid-recording the
    recording stopped and was saved (100 frames) with the reason shown.
  - Tap focus: near/far taps move focus (0.98 → 6.1 diopters on a near wall, locked in ~1 s);
    the AF mode switch costs ≤1 frame; static scene: no refocus hunting. Long-press locks.
  - Sharpness measure (contrast AF / spot watch) rewritten with bulk row reads: frame time with a
    watched spot 27.6 → 8.5 ms. Contrast AF on the backup telephoto still locks sharply.
  - Half-res preview between recordings: ~4 ms a frame instead of ~15–25.
  - Vertical EV slider (both modes, right side), effective ISO in Pro's info line.

## Built but not verified (or only partly)

- **Everything 15 Ultra-specific in 0.2.2** (zoom routes for 0.6x/3x/periscope, zoom stepping,
  HAL-recovery waits, layouts 1/2 on its main camera). Needs the friend's next diagnostics; the
  event log in Send diagnostics now shows each scan step and every open/failure.
- 4K recording start after the half-res preview. Without warm-up: one run lost ~10 frames in the
  first 1.3 s, another 2 single frames; with a full-res preview: none. With the 1 s warm-up, warm
  phone (43 °C): 4 single-frame drops at 0.7–1.0 s, then a clean run; cool phone (39–40 °C): one
  267 ms stall at 1.2 s (cause not found in the logs), then two clean runs (254 frames each). Every
  test had the camera reopened ~2.5 s before by `am start`, which real use doesn't. Check real
  recordings; if drops persist, lengthen the warm-up or keep the preview at full res in open gate.
- Tap "keep following": the re-trigger on a soft spot is untested with a real moving subject.
- LUT input conversion: maths checked (exact round trips, same XYZ path as the develop pass); not
  yet judged by eye on a decent scene (the on-device comparison scene was a blurry wall).
- Front camera orientation on the 15 Ultra looked right in the friend's screenshot (upright).
- Look editor "Clip frame" with 10-bit clips; LUT import of unusual .cube files (DOMAIN, 1D).
- AE: Priority at its limits; "limits reached" warning.

## Start here (next session)

1. Read `feedback/notes.md` (items 11–14 are this session's), then anything new in `feedback/`.
2. 0.2.2 is published as a GitHub pre-release (v0.2.2, 2026-10-06). The owner is testing it and
   collecting the friend's 15 Ultra diagnostics (it rescans on first start: lens cache version 7).
3. From the friend's next report: did the zoom routes give 0.6x/3x/4.x? Did the main camera's
   launch errors recover by themselves (event log: "retry", "layout", "streaming again")?
4. Owner decisions pending (see "Ideas" below): noise (item 5: ETTR-style AE / gain off), whether
   a 1 s REC warm-up is acceptable.

## After 0.2.2 (on main, not released yet)

- Tone balance: "Balance" button (left column, both modes; highlights −60 / shadows +30) and
  Highlights / Shadows sliders in Adjust. Curve checked numerically (Balance: +4 stops → +2.9,
  −4 → −3.6, middle grey unchanged) and all shaders compile offline (SDK glslang); not yet
  looked at on the phone.

## Known issues / owner feedback not yet addressed

- Noise from digital gain (item 5): the effective ISO is now shown and the slider turns amber from
  ≈ISO 3200, but the cause stays: Xiaomi's AE exposes RAW ~2.2–3 EV under its own rendering (even
  in daylight: ISO 50 1/2300 + 3 EV of gain), and we add it back digitally.
- Front camera with session layout 2 (RAW only, the last fallback) comes out magenta and unshaded:
  Xiaomi's front ISP runs no AWB/shading without the metering stream. Only reached after layouts 0
  and 1 fail twice each.
- Native telephoto AF ignores tap regions (both AF modes) → taps there use contrast AF (~3 s).
  Region-aware native taps probably need Xiaomi vendor tags / session op mode.
- Contrast AF coarse pass is affected by lens lag (values from the previous position); the fine
  pass corrects it, but the curve logs look odd.
- No portrait orientation (landscape-locked UI).
- Auto gain only learns at EV 0 in AE Auto; AUTO_BIAS_EV (0.3) fitted on one daylight scene.
- `afverbose`, `rawlens`, `failcam`, `fakeheat`, `layout`, contrast-AF curve logging are debug aids.

## Ideas discussed, not started

- **ETTR-style exposure** (for item 5's noise): drive sensor exposure from the RAW histogram and
  lower the digital gain by the same amount; 2–3 EV more light in daylight, less highlight
  headroom. The owner is fine with the noise for now and chose tone balance instead (above).
- Lighter 4K pipeline for heat: prep pass into R16F isn't allowed as an image format in GLES 3.1;
  next candidates: compute the LUT view at half resolution while recording log, zero-copy RAW
  upload (AHardwareBuffer, needs NDK), fewer full-res RGBA16F passes.
- "AuthRec Log": a sensor-fitted log curve plus conversion LUTs to Apple Log/S-Log3.
- RAW (MCRAW-style) recording; storage-bandwidth bound (~250–750 MB/s at open gate).
- Logical-RAW zoom route for phones whose physical streams don't work: RAW on the logical camera at
  CONTROL_ZOOM_RATIO, sensor metadata from the active physical camera (needs per-frame CFA/levels).
- Portrait UI; on-screen histogram/zebras; per-lens calibration of the auto-gain bias via refshot.

## Testing notes

- One phone, over USB; it runs hot while the camera is open. See CLAUDE.md "Mind the heat".
- `am start` hooks pause/resume the activity (camera reopens); use `adb shell input tap` for UI.
- The event log (`adb shell run-as com.authrec cat files/events.log`) is the quickest way to see
  what the camera did; it's also what testers send via Send diagnostics.
