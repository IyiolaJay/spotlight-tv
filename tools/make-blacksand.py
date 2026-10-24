"""Paint a black sand beach at dusk (Reynisfjara-like). Usage: .venv/bin/python tools/make-blacksand.py wallpaper/blacksand.jpg"""
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1920, 1080
HZ = 520            # horizon
SHORE = 800         # where the surf meets wet sand
SUNX = 1300
rng = np.random.default_rng(69)
y, x = np.mgrid[0:H, 0:W].astype(np.float32)

def hexc(h):
    h = h.lstrip('#'); return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], np.float32) / 255

def lerp_stops(t, stops):
    out = np.zeros(t.shape + (3,), np.float32)
    for (p0, c0), (p1, c1) in zip(stops[:-1], stops[1:]):
        m = (t >= p0) & (t <= p1)
        u = ((t - p0) / max(1e-6, p1 - p0))[..., None]
        out[m] = (hexc(c0) * (1 - u) + hexc(c1) * u)[m]
    return out

def blob(cx, cy, rx, ry):
    return np.exp(-(((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2) * 2.2)

def mask_from(polys, blur=0.8):
    m = Image.new('L', (W, H), 0); d = ImageDraw.Draw(m)
    for p in polys: d.polygon(p, fill=255)
    return np.asarray(m.filter(ImageFilter.GaussianBlur(blur)), np.float32)[..., None] / 255

def smooth_noise(n, scale, seed):
    r = np.random.default_rng(seed)
    pts = r.random(n // scale + 3)
    xs = np.arange(n) / scale
    i = xs.astype(int); f = xs - i
    f = f * f * (3 - 2 * f)
    return pts[i] * (1 - f) + pts[i + 1] * f

def fbm(n, seed, scales=(220, 90, 37, 15), amps=(1, .5, .25, .12)):
    return sum(a * smooth_noise(n, s, seed + k) for k, (s, a) in enumerate(zip(scales, amps))) / sum(amps)

img = np.zeros((H, W, 3), np.float32)

# Sky: slate blue overhead, muted rose and amber at the horizon
sky = lerp_stops(np.clip(y / HZ, 0, 1), [(0, '#263649'), (0.35, '#3e5167'), (0.68, '#7c7b8c'), (0.86, '#b58f88'), (1, '#d8a98b')])
img[:] = sky
img += blob(SUNX, HZ - 10, 520, 250)[..., None] * hexc('#f3c89c') * 0.40
img += blob(SUNX, HZ - 4, 150, 55)[..., None] * hexc('#ffe2b8') * 0.42

# Clouds: long soft streaks, lit towards the sun
cl = Image.new('L', (W, H), 0); cd = ImageDraw.Draw(cl)
for _ in range(28):
    cy = rng.uniform(80, HZ - 80); cx = rng.uniform(-200, W + 200)
    w = rng.uniform(260, 820); h = rng.uniform(12, 42)
    cd.ellipse([cx - w, cy - h, cx + w, cy + h], fill=int(rng.uniform(70, 160)))
cl = np.asarray(cl.filter(ImageFilter.GaussianBlur(22)), np.float32)[..., None] / 255
lit = np.clip(1.2 - np.abs(x - SUNX) / 1300, 0.25, 1)[..., None]
img = img * (1 - cl * 0.55) + (hexc('#c9b4b4') * lit + hexc('#56637a') * (1 - lit)) * cl * 0.55

# Ocean: sky reflection, darker towards the shore, broken sun path
t = np.clip((y - HZ) / (SHORE - HZ), 0, 1)
sea = lerp_stops(t, [(0, '#8a8e99'), (0.12, '#5e717f'), (0.55, '#3a4f5d'), (1, '#2c3d49')])
sea_m = (y >= HZ)[..., None].astype(np.float32)
img = img * (1 - sea_m) + sea * sea_m
for k in range(16):  # swells tightening towards the horizon
    yy = HZ + 6 + (k ** 1.75) * 2.4
    wob = 3 * np.sin(x / (150 + 9 * k) + k * 2.1) + 2 * np.sin(x / 47 + k)
    band = np.exp(-((y - yy - wob) / (1.0 + k * 0.32)) ** 2)
    img += (band * ((y > HZ) & (y < SHORE)))[..., None] * hexc('#a9b6bf') * 0.055
gl = np.zeros((H, W), np.float32)
for _ in range(1100):
    gy = HZ + abs(rng.normal(0, 1)) * 140
    if gy > SHORE - 40: continue
    gx = rng.normal(SUNX, 36 + (gy - HZ) * 0.85)
    ln = rng.uniform(5, 34) * (1 + (gy - HZ) / 140)
    gl[int(gy), int(max(0, gx - ln / 2)):int(min(W, gx + ln / 2))] += rng.uniform(0.2, 0.85)
gl = np.asarray(Image.fromarray(np.clip(gl * 255, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.1)), np.float32)[..., None] / 255
img += gl * hexc('#ffdcb2') * 0.5
img += np.exp(-((y - HZ) / 9) ** 2)[..., None] * hexc('#d8c3b0') * 0.07   # haze on the horizon line

# Far headland, hazy
pts = [(1440, HZ + 2)] + [(1440 + i * 48, HZ - 4 - 30 * np.sin(i / 3.1) ** 2 - rng.uniform(0, 7)) for i in range(11)] + [(W, HZ + 2)]
hd = mask_from([pts], 1.5)
img = img * (1 - hd * 0.8) + hexc('#6f6c7c') * hd * 0.8

def basalt(cx, width, peak, seed, base_col, haze, lean=0.0):
    """A sea stack built from basalt columns: stepped, uneven tops, a lit edge towards the sun."""
    r = np.random.default_rng(seed)
    cols, xs = [], cx - width / 2
    while xs < cx + width / 2:
        cw = r.uniform(9, 26); cols.append((xs, cw)); xs += cw
    poly_top = []
    for (cx0, cw) in cols:
        f = abs((cx0 + cw / 2) - cx) / (width / 2)          # 0 at the middle, 1 at the edges
        top = peak + (SHORE - 60 - peak) * (f ** 1.6) * r.uniform(0.75, 1.1) + r.uniform(-14, 14)
        top += lean * (cx0 - cx)
        slant = r.uniform(-6, 6)
        poly_top += [(cx0, top + slant), (cx0 + cw, top - slant)]
    poly = [(cx - width / 2 - 14, SHORE - 20)] + poly_top + [(cx + width / 2 + 14, SHORE - 20)]
    m = mask_from([poly], 0.7)
    # each column a slightly different shade, faint vertical joints
    shade = np.ones((W,), np.float32)
    for (cx0, cw) in cols:
        a, b = int(max(0, cx0)), int(min(W, cx0 + cw))
        shade[a:b] = r.uniform(0.82, 1.12)
        if a < W: shade[a] *= 0.75
    shade = shade[None, :, None]
    rim = (np.clip((x - (cx + width * 0.15)) / (width * 0.35), 0, 1) ** 2)[..., None] * 0.20
    c = hexc(base_col) * shade + hexc('#c99a7c') * rim * (y < SHORE - 40)[..., None]
    c = c * (1 - haze) + hexc('#7a7d8b') * haze
    return m, c

for cx, wd, pk, sd, col, hz, ln in [(1010, 120, 372, 11, '#20262e', 0.26, 0.05),
                                     (860, 170, 300, 12, '#1d2229', 0.14, -0.04),
                                     (705, 230, 262, 13, '#171b21', 0.04, 0.02)]:
    m, c = basalt(cx, wd, pk, sd, col, hz, ln)
    img = img * (1 - m) + c * m

# Columned cliff on the left, sloping down to the beach
r2 = np.random.default_rng(5)
top_pts, xs = [], 0.0
while xs < 520:
    cw = r2.uniform(12, 34)
    f = xs / 520
    top = 205 + 520 * (f ** 2.4) + r2.uniform(-12, 12)
    top_pts += [(xs, top + r2.uniform(-5, 5)), (xs + cw, top + r2.uniform(-5, 5))]
    xs += cw
cliff_poly = [(0, 190)] + top_pts + [(560, SHORE - 10), (600, SHORE + 30), (0, SHORE + 30)]
cm = mask_from([cliff_poly], 0.9)
shade = np.ones((W,), np.float32); xs = 0.0
while xs < 620:
    cw = r2.uniform(12, 34); a, b = int(xs), int(min(W, xs + cw))
    shade[a:b] = r2.uniform(0.8, 1.15); shade[a] *= 0.7; xs += cw
cliff_col = hexc('#1a1d23') * shade[None, :, None] * (0.85 + 0.25 * np.clip((y - 200) / 600, 0, 1))[..., None]
img = img * (1 - cm) + cliff_col * cm

# Surf: soft white water from horizontally stretched noise, banded along wavy lines, then a lacy wash
def streaks(seed, sy, sx, lo, hi):
    r = np.random.default_rng(seed)
    n = Image.fromarray((r.random((H // sy, W // sx)) * 255).astype(np.uint8)).resize((W, H), Image.BICUBIC)
    n = np.asarray(n.filter(ImageFilter.GaussianBlur(1.0)), np.float32) / 255
    return np.clip((n - lo) / (hi - lo), 0, 1)
foam = np.zeros((H, W), np.float32)
for k, (fy, amp, th, inten) in enumerate([(SHORE - 74, 6, 5, 0.35), (SHORE - 46, 9, 7, 0.6), (SHORE - 18, 12, 10, 0.9), (SHORE + 10, 8, 6, 0.5)]):
    curve = fy + amp * (fbm(W, 40 + k) - 0.5) * 2 + 3 * np.sin(np.arange(W) / 37 + k)
    band = np.exp(-((y - curve[None, :]) / th) ** 2)
    foam += inten * band * streaks(200 + k, 6, 40, 0.35, 0.8)
wash_edge = SHORE + 30 + 22 * (fbm(W, 99) - 0.5) * 2
wash = np.clip((wash_edge[None, :] - y) / 30, 0, 1) * (y > SHORE - 4) * 0.16
lace = np.exp(-((y - wash_edge[None, :]) / 2.6) ** 2) * streaks(301, 3, 14, 0.45, 0.75) * 0.75
foam += wash + lace
foam = np.asarray(Image.fromarray(np.clip(foam * 255, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.4)), np.float32)[..., None] / 255
foam *= np.clip((x - 520) / 140, 0, 1)[..., None]          # no surf against the cliff face

# Sand: a narrow dark gloss where it is wet, dry black sand below with faint wind ripples and grain
sand_t = np.clip((y - (SHORE + 20)) / (H - SHORE - 20), 0, 1)
sand = lerp_stops(sand_t, [(0, '#1f2227'), (0.3, '#17191e'), (1, '#111216')])
sand_m = np.clip((y - (SHORE + 4)) / 12, 0, 1)[..., None]
wet = np.exp(-((y - (SHORE + 40)) / 20) ** 2)[..., None]
sand = sand + wet * (hexc('#4a5463') * 0.16 + blob(SUNX, SHORE + 44, 200, 34)[..., None] * hexc('#e8b896') * 0.30)
ripple = np.sin(y / 5.5 + 9 * fbm(W, 7, (300, 120), (1, .5))[None, :]) * 0.005 * (0.4 + fbm(W, 8, (90, 30), (1, .5))[None, :]) * (sand_t > 0.08)
sand += ripple[..., None]
sand += (rng.random((H, W)).astype(np.float32) - 0.5)[..., None] * 0.03
sand += (rng.random((H, W)) > 0.9994).astype(np.float32)[..., None] * 0.28 * (1 - sand_t[..., None])
img = img * (1 - sand_m) + sand * sand_m
img = img + foam * hexc('#e9ecef') * 0.9

# Gentle vignette
vig = 1 - 0.26 * (((x - W / 2) / (W / 1.2)) ** 2 + ((y - H / 2) / (H / 1.1)) ** 2)
img *= vig[..., None]
img += (rng.random((H, W, 1)).astype(np.float32) - 0.5) / 255 * 1.5   # dither
out = Image.fromarray(np.clip(img * 255 + 0.5, 0, 255).astype(np.uint8), 'RGB')
out.save(sys.argv[1], quality=90, subsampling=0)
a = np.asarray(out.convert('L'), np.float32)
print('mean luminance', round(float(a.mean()), 1), '/255; left third', round(float(a[:, :640].mean()), 1), 'right two-thirds', round(float(a[:, 640:].mean()), 1))
