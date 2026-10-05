# Handoff: state as of v0.2.0 (2026-10-05)

How it got here: built in one long session from a capability bench → live RAW preview → recording
→ features, testing each step on the owner's Xiaomi 14 over adb. Architecture and device quirks:
see **CLAUDE.md**. New feedback/diagnostics/samples: **feedback/**.

## Working and verified on the Xiaomi 14

- Open gate 4096×3072 from RAW_SENSOR (14-bit) at 24/25/30 fps, 0 dropped frames over 15–30 s
  recordings (one warm-up drop in the first 0.25 s), incl. strongest chroma NR.
- HEVC Main10 is genuinely 10-bit (726 distinct Y levels, low bits uniform); HEVC 8-bit and H.264
  High at open gate also work. 150 Mbps measured. AAC stereo 48 kHz synced on the camera clock.
- Superpixel 2048×1536 mode; Apple Log / S-Log3 / LogC3; built-in looks, user looks, imported
  .cube (tetrahedral); look editor with .cube export; LUT strength/saturation/vibrance.
- Lenses: 0.6x (hidden id 3), 1x (0), 2.6x (logical 5 → physical 4, native continuous AF; backup =
  direct id 4 with contrast AF), Front (1). Owner confirmed AF and tap focus work, incl. low light.
- Colour vs ISP JPEG: hue within ~2.5–3°; auto gain matches ISP midtone brightness.
- Chroma NR: −70 % colour noise in a flat patch, luma texture unchanged. Defect-pixel repair.
- Priority AE panel (limits, ✕ Done, ⚙ to reopen), unified EV ±5, Simple mode with 4K/2K + Adjust.
- Release v0.2.0 published (signed), README with install steps.

## Built but not verified (or only partly)

- **Send diagnostics…** share sheet: never opened on a device yet.
- Front camera orientation: last change reverted to the original formula; owner reported the 180°
  version upside down in landscape. Needs the owner's eyes.
- Ultrawide in the dark: owner said "fixed" after moving to direct id 3; watchdog untested in anger.
- Native-AF telephoto in the dark (does the logical camera switch sensors and trigger the fallback?).
- AE: Priority behaviour indoors at its limits; "limits reached" warning.
- Look editor "Clip frame" with 10-bit clips; LUT import of unusual .cube files (DOMAIN, 1D).
- Anything on a non-Xiaomi or non-Snapdragon phone; the Xiaomi 15 Ultra (friend testing now).

## Start here (next session)

1. Read `feedback/notes.md` (2026-10-05): 10 triaged items from both phones with diagnoses.
2. 0.2.1 changes (probe results in diagnostics, dark-room probe fix, logical-stream retry,
   launcher-extras fix) are verified on the X14 (all 5 routes pass in a dark room) but not yet
   released; publish it so the friend's 15 Ultra can send useful diagnostics (ask the owner first).
3. Biggest owner pain points: noise from digital gain (item 5), heat (item 7), EV slider in Pro
   (item 6), tap keeps AF Auto (item 8). 15 Ultra: missing lenses (item 1), lens-switch freeze (item 2).

## Known issues / owner feedback not yet addressed

- Owner: "exposure control is a bit iffy" during recording and in general; AE/EV/AF "needs work",
  UI/UX overall alpha. Get specifics from feedback/ before redesigning.
- Native telephoto AF ignores tap regions (both AF modes) → taps fall back to contrast AF (~3 s).
  Region-aware native taps probably need Xiaomi vendor tags / session op mode (MotionCam has a
  vendor-tag editor and `sessionOpMode` per camera; Qualcomm `org.codeaurora.qcamera3.*` session
  keys are listed by `cmd=dumpcams`).
- Contrast AF is slow (~3 s per tap) and the "AF: Auto" software monitor refocuses with a visible
  local search; centre AF on a diagonal subject picks the dominant detail.
- Brief fps dip right after taps on the native-AF telephoto (seen with picture AF; video AF looked
  fine at 29 fps; re-check).
- No portrait orientation (all UI landscape-locked; holding the phone upright shows the image
  sideways).
- Auto gain is per-scene adaptive and only learns at EV 0; in very high-contrast scenes Xiaomi
  underexposes RAW more, so footage can still come out darker than the ISP's.
- AUTO_BIAS_EV (0.3) was fitted on one overcast daylight scene on the main camera only.
- `afverbose`, `rawlens`, contrast-AF curve logging are debug aids; keep them out of the UI.

## Ideas discussed, not started

- "AuthRec Log": a sensor-fitted log curve (~95 % code-value use vs ~66–76 % for the standard
  curves) plus conversion LUTs to Apple Log/S-Log3 for Resolve.
- RAW (MCRAW-style) recording mode; storage-bandwidth bound (~250–750 MB/s at open gate).
- Portrait UI; on-screen histogram/zebras; per-lens calibration of the auto-gain bias via refshot.
- Zero-copy RAW upload (AHardwareBuffer) to cut ~6 ms/frame.

## Suggested way to split work between agents

Independent tracks that touch mostly separate files (use git worktrees/branches):
1. **Exposure/AE** (`CameraActivity` exposure section, `Renderer` auto gain): act on owner feedback.
2. **AF** (`SoftwareAf`, `RawCamera` AF request code, vendor-tag experiments for native taps).
3. **Device compatibility** (`LensProbe`, `RawCamera` fallbacks, `Diagnostics`) driven by the
   15 Ultra diagnostics.
4. **UI/UX** (`CameraActivity` layout code): only after 1–3 settle, since it touches the same file.
Every track tests on the phone over adb (one phone: coordinate who is using it).
