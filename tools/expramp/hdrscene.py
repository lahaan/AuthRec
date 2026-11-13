#!/usr/bin/env python3
"""Writes the frames of an HDR test scene (10-bit video-range grey in 16-bit words, PQ-coded nits) to stdout for ffmpeg.

A dim room (6 nits) with four grey blocks (1.5, 6, 25, 60 nits) and a window of up to 1600 nits.
180 s, looping:
   0– 40 s  A  window a quarter of the frame, fading 1600 → 100 → 1600 nits
  40– 80 s  B  window at 1600 nits, growing from 2 % to 64 % of the frame and back
  80–160 s  C  the whole frame one grey, fading 1000 → 45 → 1000 nits (4.5 stops in 40 s each way)
 160–180 s  D  hard cuts every 5 s: plain 100 nits / the backlit room / plain 100 nits / the room

Make the clip next to scene.html (it is not kept in the repository), then open the page in
Safari on a Mac with an XDR display at full brightness and keep the Mac awake meanwhile:

    cd tools/expramp
    python3 hdrscene.py | ffmpeg -y -f rawvideo -pix_fmt gray10le -s 756x492 -r 30 -color_range tv -i - \
      -vf "scale=in_range=limited:out_range=limited,format=yuv420p10le" -c:v libx265 -preset fast -crf 14 -tag:v hvc1 \
      -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
      -x265-params "colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:range=limited:hdr10=1:repeat-headers=1:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(16000000,1):max-cll=1600,400" \
      scene.mp4
    open -a Safari scene.html; caffeinate -d -i -t 3600 &

scene.html plays it in step with the wall clock (time.time() % 180 is the clip's second), so a
phone log (explog, see ramp.py) can be lined up with the phases. Phone about 60 cm back for the
2.6x to see the whole screen.
"""
import math, struct, sys
W, H, FPS, SECS = 756, 492, 30, 180

def pq(nits):
    y = max(nits, 0.0) / 10000.0
    m1, m2, c1, c2, c3 = 2610 / 16384, 2523 / 4096 * 128, 3424 / 4096, 2413 / 4096 * 32, 2392 / 4096 * 32
    p = y ** m1
    # As a 10-bit video-range code (64–940), which is what the encoder is given.
    return int(round(64 + 876 * ((c1 + c2 * p) / (1 + c3 * p)) ** m2))

def px(nits, n): return struct.pack('<H', pq(nits)) * n
def tri(p): return 2 * p if p < 0.5 else 2 - 2 * p   # 0 → 1 → 0

BG = 6.0
BLOCKS = [(0.06, 0.24, 1.5), (0.28, 0.46, 6.0), (0.50, 0.68, 25.0), (0.72, 0.90, 60.0)]  # y0, y1, nits; x 5–22 %
BX0, BX1 = int(0.05 * W), int(0.22 * W)

def room(win_nits, size):
    """The room with a window `size` of the frame's width and height, centred at 62 % / 50 %."""
    ww, wh = min(int(size * W), W - BX1 - 4), int(size * H)
    wx0 = min(max(int(0.62 * W - ww / 2), BX1 + 4), W - ww); wy0 = int(0.5 * H - wh / 2)
    rows = []
    for y in range(H):
        block = next((n for y0, y1, n in BLOCKS if y0 * H <= y < y1 * H), None)
        inwin = wy0 <= y < wy0 + wh
        row = px(BG, BX0) + px(block if block is not None else BG, BX1 - BX0)
        if inwin: row += px(BG, wx0 - BX1) + px(win_nits, ww) + px(BG, W - wx0 - ww)
        else: row += px(BG, W - BX1)
        rows.append(row)
    return b''.join(rows)

def plain(nits): return px(nits, W * H)

out = sys.stdout.buffer
cache = {}
for i in range(FPS * SECS):
    t = i / FPS
    if t < 40: key = ('room', round(1600 * 2 ** (-4 * tri(t / 40)), 1), 0.5)
    elif t < 80: key = ('room', 1600.0, round(0.15 + 0.65 * tri((t - 40) / 40), 3))
    elif t < 160: key = ('plain', round(1000 * 2 ** (-4.5 * tri((t - 80) / 80)), 1))
    else: key = ('plain', 100.0) if int((t - 160) / 5) % 2 == 0 else ('room', 1600.0, 0.5)
    if key not in cache:
        cache.clear()
        cache[key] = plain(key[1]) if key[0] == 'plain' else room(key[1], key[2])
    out.write(cache[key])
