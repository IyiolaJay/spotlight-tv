"""Bakes the Spotlight home's scrim into the beach landscape, so the TV draws one full-screen image
instead of an image plus two full-screen gradients on every frame.

The scrim matches the design (SpotlightBeach.dc.html, .sf-scrim): dark on the left behind the app
list, fading out by mid-screen, with a light shade along the top and bottom edges.

Any picture works: it is cropped to fill 16:9 (centred), then scaled to 1920 x 1080.

Usage: .venv/bin/python tools/bake-landscape.py [picture]     (default: wallpaper/blacksand.jpg, the painted beach)
       -> launcher/app/src/main/res/drawable-nodpi/landscape.jpg
"""
import sys

import numpy as np
from PIL import Image

SRC = sys.argv[1] if len(sys.argv) > 1 else "wallpaper/blacksand.jpg"
OUT = "launcher/app/src/main/res/drawable-nodpi/landscape.jpg"
SHADE = np.array([6, 5, 8], dtype=np.float32)


def ramp(n, stops):
    """Alpha along n pixels from CSS-style (position 0..1, alpha) stops."""
    pos, alpha = zip(*stops)
    return np.interp((np.arange(n) + 0.5) / n, pos, alpha).astype(np.float32)


def shade(img, alpha):
    return img * (1 - alpha[..., None]) + SHADE * alpha[..., None]


def cover(im, w, h):
    """Crops the centre to w:h, then scales: fills the screen without stretching."""
    scale = max(w / im.width, h / im.height)
    cw, ch = round(w / scale), round(h / scale)
    left, top = (im.width - cw) // 2, (im.height - ch) // 2
    return im.crop((left, top, left + cw, top + ch)).resize((w, h), Image.LANCZOS)


img = np.asarray(cover(Image.open(SRC).convert("RGB"), 1920, 1080), dtype=np.float32)
h, w, _ = img.shape
# CSS paints the last listed background first: the top/bottom shade, then the left-hand one over it
across = ramp(w, [(0, .80), (.30, .60), (.52, .12), (1, 0)])
down = ramp(h, [(0, .38), (.20, 0), (.80, 0), (1, .30)])
img = shade(img, np.broadcast_to(down[:, None], (h, w)))
img = shade(img, np.broadcast_to(across[None, :], (h, w)))
# A touch of noise so the long dark gradient doesn't band once it's 8-bit
img += np.random.default_rng(69).uniform(-0.5, 0.5, img.shape).astype(np.float32)
Image.fromarray(np.clip(img + 0.5, 0, 255).astype(np.uint8)).save(OUT, quality=90, optimize=True)
print(OUT)
