# Reference shots (colour / exposure calibration)

Captures one RAW frame and the phone's own ISP JPEG of the same instant, then re-implements the
app's pipeline in Python to compare against the JPEG. This is how the colour matrix choice and
the auto-gain bias (`Renderer.AUTO_BIAS_EV`) were validated: hue matched within ~2.5–3°.

```bash
# on the phone: pick the lens / settings first, then
adb shell am start -n com.authrec/.CameraActivity --es cmd refshot
# wait ~10 s (the preview pauses while it captures), then
mkdir -p /tmp/ref && cd /tmp/ref
for f in raw.bin meta.json isp.jpg; do adb pull /sdcard/Android/data/com.authrec/files/ref/$f .; done
python3 -m venv venv && ./venv/bin/pip install numpy pillow
./venv/bin/python /path/to/AuthRec/tools/refshot/ref.py          # colour comparison + compare.jpg
./venv/bin/python /path/to/AuthRec/tools/refshot/ref.py --fit    # which pre-log gain matches the ISP
```

`raw.bin` is tightly packed 16-bit Bayer; `meta.json` holds black/white levels, white-balance
gains, the ISP colour matrix, lens-shading map and the sensor's calibration matrices
(see `RawCamera.referenceMeta`). Use daylight scenes with a range of colours; the JPEG has
local tone mapping, so compare hue/chroma rather than brightness.
