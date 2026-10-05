"""Replicates AuthRec's RAW pipeline on a reference shot and compares it with the ISP JPEG.

Variants:
  isp_ccm : what the app does now (ISP's per-frame COLOR_CORRECTION_TRANSFORM)
  dng_fm  : DNG-style forward matrices interpolated by colour temperature
Output: per-variant colour error stats vs the JPEG, plus side-by-side images.
"""
import json
import sys
import numpy as np
from PIL import Image

m = json.load(open("meta.json"))
W, H = m["width"], m["height"]
raw = np.fromfile("raw.bin", dtype="<u2").reshape(H, W).astype(np.float32)

rx, ry = m["redOffset"]
black = np.array(m["black"], np.float32).reshape(2, 2)  # [row][col]
white = m["white"]

# Per-position black level, normalise to 0..1.
bl = np.tile(black, (H // 2, W // 2))
x = (raw - bl) / (white - bl)

# Superpixel planes (colour order R, G_even (red row), G_odd (blue row), B).
R = x[ry::2, rx::2]
Ge = x[ry::2, 1 - rx::2]
Go = x[1 - ry::2, rx::2]
B = x[1 - ry::2, 1 - rx::2]
h, w = R.shape

# Lens shading map → bilinear upsample to the plane size.
cols, rows = m["shadingCols"], m["shadingRows"]
sh = np.array(m["shading"], np.float32).reshape(rows, cols, 4)
def upsample(g):
    yi = np.linspace(0, rows - 1, h)
    xi = np.linspace(0, cols - 1, w)
    y0 = np.clip(yi.astype(int), 0, rows - 2); fy = (yi - y0)[:, None]
    x0 = np.clip(xi.astype(int), 0, cols - 2); fx = (xi - x0)[None, :]
    a = g[y0][:, x0] * (1 - fx) + g[y0][:, x0 + 1] * fx
    b = g[y0 + 1][:, x0] * (1 - fx) + g[y0 + 1][:, x0 + 1] * fx
    return a * (1 - fy) + b * fy
wb = np.array(m["wbGains"], np.float32)
gains = [upsample(sh[:, :, c]) * wb[c] for c in range(4)]
clip = np.minimum(np.minimum(gains[0], gains[1]), np.minimum(gains[2], gains[3]))
planes = [np.minimum(p * g, clip) for p, g in zip([R, Ge, Go, B], gains)]
cam = np.stack([planes[0], 0.5 * (planes[1] + planes[2]), planes[3]], -1)  # white-balanced camera RGB

def M(key):
    return np.array(m[key], np.float64).reshape(3, 3)

XYZ_TO_SRGB = np.array([[3.2404542, -1.5371385, -0.4985314],
                        [-0.9692660, 1.8760108, 0.0415560],
                        [0.0556434, -0.2040259, 1.0572252]])
BRADFORD_D50_TO_D65 = np.array([[0.9555766, -0.0230393, 0.0631636],
                                [-0.0282895, 1.0099416, 0.0210077],
                                [0.0122982, -0.0204830, 1.3299098]])

def cct_from_xy(x, y):  # McCamy
    n = (x - 0.3320) / (0.1858 - y)
    return 449 * n ** 3 + 3525 * n ** 2 + 6823.3 * n + 5520.33

ILLUM_CCT = {17: 2856.0, 21: 6504.0, 23: 5003.0, 20: 5503.0, 22: 7504.0}

def dng_forward_matrix():
    """DNG spec: find the CCT of the as-shot neutral, interpolate matrices in 1/CCT."""
    neutral = np.array(m["neutral"], np.float64)
    t1, t2 = ILLUM_CCT[m["illuminant1"]], ILLUM_CCT[m["illuminant2"]]
    cm1 = M("calibration1") @ M("colorMatrix1")
    cm2 = M("calibration2") @ M("colorMatrix2")
    cct = 5000.0
    for _ in range(20):
        wgt = np.clip((1 / cct - 1 / t2) / (1 / t1 - 1 / t2), 0, 1)
        cm = wgt * cm1 + (1 - wgt) * cm2
        xyz = np.linalg.solve(cm, neutral)
        xx, yy = xyz[0] / xyz.sum(), xyz[1] / xyz.sum()
        cct = cct_from_xy(xx, yy)
    wgt = np.clip((1 / cct - 1 / t2) / (1 / t1 - 1 / t2), 0, 1)
    fm = wgt * M("forwardMatrix1") + (1 - wgt) * M("forwardMatrix2")
    return fm, cct, wgt

def view(lin):
    v = np.maximum(lin * 0.6, 0)
    f = np.clip((v * (2.51 * v + 0.03)) / (v * (2.43 * v + 0.59) + 0.14), 0, 1)
    return np.where(f <= 0.0031308, 12.92 * f, 1.055 * np.power(f, 1 / 2.4) - 0.055)

def srgb_to_lab(rgb):
    lin = np.where(rgb <= 0.04045, rgb / 12.92, ((rgb + 0.055) / 1.055) ** 2.4)
    to_xyz = np.linalg.inv(XYZ_TO_SRGB)
    xyz = lin @ to_xyz.T / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], -1)

jpeg = np.asarray(Image.open("isp.jpg").convert("RGB").resize((w, h), Image.BOX), np.float32) / 255

fm, cct, wgt = dng_forward_matrix()
variants = {
    "isp_ccm": cam @ M("ccm").T,
    "dng_fm": cam @ (XYZ_TO_SRGB @ BRADFORD_D50_TO_D65 @ fm).T,
}
print(f"as-shot CCT ≈ {cct:.0f} K (weight on D65 matrix {wgt:.2f})")

# Compare per block: hue and chroma only (tone mapping differs by design).
by, bx = 12, 16
def blocks(img):
    return img[: h // by * by, : w // bx * bx].reshape(by, h // by, bx, w // bx, 3).mean((1, 3))
ref_lab = srgb_to_lab(blocks(jpeg))

images = [jpeg]
for name, lin in variants.items():
    out = view(lin)
    images.append(out)
    lab = srgb_to_lab(blocks(out))
    c_ref = np.hypot(ref_lab[..., 1], ref_lab[..., 2])
    c_out = np.hypot(lab[..., 1], lab[..., 2])
    mask = c_ref > 8  # hue is meaningless for near-neutral blocks
    dh = np.degrees(np.angle(np.exp(1j * (np.arctan2(lab[..., 2], lab[..., 1]) - np.arctan2(ref_lab[..., 2], ref_lab[..., 1])))))
    dab = np.hypot(lab[..., 1] - ref_lab[..., 1], lab[..., 2] - ref_lab[..., 2])
    neutral = c_ref < 6
    print(f"{name:8s}: mean Δab {dab.mean():5.2f} | hue error {np.abs(dh[mask]).mean():5.1f}° (median {np.median(np.abs(dh[mask])):4.1f}°) "
          f"| chroma ratio {np.median(c_out[mask] / c_ref[mask]):.2f} | neutral blocks' chroma {c_out[neutral].mean() if neutral.any() else float('nan'):.1f} "
          f"(JPEG {c_ref[neutral].mean() if neutral.any() else float('nan'):.1f})")
    print(f"           mean L*: ours {lab[..., 0].mean():.1f} vs JPEG {ref_lab[..., 0].mean():.1f}")

side = np.concatenate([np.clip(i, 0, 1) for i in images], axis=1)
Image.fromarray((side * 255).astype(np.uint8)).resize((side.shape[1] // 2, side.shape[0] // 2), Image.BOX).save("compare.jpg", quality=92)
print("wrote compare.jpg: JPEG | " + " | ".join(variants))

# ---- Exposure normalisation: which linear gain best matches the JPEG's midtones? ----
if "--fit" in sys.argv:
    lin = variants["isp_ccm"]
    ref_L = ref_lab[..., 0]
    mid = (ref_L > 30) & (ref_L < 75)
    print(f"{mid.sum()} mid-tone blocks")
    for stops in np.arange(0, 4.01, 0.25):
        lab = srgb_to_lab(blocks(view(lin * 2 ** stops)))
        err = np.median(lab[..., 0][mid] - ref_L[mid])
        c_ref = np.hypot(ref_lab[..., 1], ref_lab[..., 2]); c = np.hypot(lab[..., 1], lab[..., 2])
        mask = c_ref > 8
        print(f"  +{stops:4.2f} EV: median L* diff {err:+6.1f}, chroma ratio {np.median(c[mask] / c_ref[mask]):.2f}")
