#!/usr/bin/env python3
"""Reads an `explog` capture (see below) and lists what the exposure and the picture did.

The rig: open ramp.html full screen on the Mac (a grey field that fades 5 stops down and back
every 90 s), stand the phone in front of it on USB, then

    adb logcat -c
    adb shell am start -n com.authrec/.CameraActivity --ei explog 2850 --el t $RANDOM
    sleep 99
    adb logcat -d -s AuthRec:I | grep " EXP " | sed 's/.*EXP //' > ramp.txt
    tools/expramp/ramp.py ramp.txt

A still, evenly lit target makes the RAW average exact, so "shown" (log2 of the RAW average plus
all digital gain) is the brightness the picture comes out at: it should move smoothly while the
camera's ISO and shutter step about underneath. Add `--ef evostep -0.1667` to the hook to have
EVO ask a sixth of a stop of the sensor and take it back every 75 frames.
"""
import math, sys

rows = [l.split() for l in open(sys.argv[1])]
def f(r, k): return float(r[r.index(k) + 1])
t0 = float(rows[0][0])
print('frames', len(rows), 'with their own capture result', sum(1 for r in rows if r[5] == 'own'),
      'over %.1f s' % ((float(rows[-1][0]) - t0) / 1000))
print('    t   iso shutter    raw   auto night  view steady total  shown')
for i, r in enumerate(rows):
    if i % 90: continue
    print('%5.1f %5d 1/%-5.0f %.4f %5.2f %5.2f %+5.2f %+5.2f %5.2f %+6.2f' % (
        (float(r[0]) - t0) / 1000, f(r, 'iso'), 1e9 / f(r, 'ns'), f(r, 'raw'), f(r, 'auto'), f(r, 'night'),
        f(r, 'view'), f(r, 'steady') if 'steady' in r else 0, f(r, 'total'), math.log2(f(r, 'raw')) + f(r, 'total')))
steps = jumps = big = 0
worst = (0.0, 0.0)
prev = None
for i, r in enumerate(rows):
    e = math.log2(f(r, 'iso') * f(r, 'ns'))
    shown = math.log2(f(r, 'raw')) + f(r, 'total')
    if prev and i > 20:
        if abs(e - prev[0]) > 0.08: steps += 1
        d = abs(shown - prev[1])
        if d > 0.08: jumps += 1
        if d > 0.15: big += 1
        if d > worst[0]: worst = (d, (float(r[0]) - t0) / 1000)
    prev = (e, shown)
print('exposure steps over 0.08 EV: %d; frames the picture jumped by over 0.08 EV: %d, over 0.15 EV: %d; largest %.2f EV at %.1f s'
      % (steps, jumps, big, worst[0], worst[1]))
