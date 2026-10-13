#!/usr/bin/env python3
"""Frame raw phone screenshots in a drawn Galaxy S24 Ultra-style body.

Reads <raw_dir>/<screen>-<theme>.png (screen: home, history, profile;
theme: light, dark), as taken with `adb exec-out screencap -p`, and writes:

  <out_dir>/<screen>-<theme>.png   one framed phone, transparent background
  <out_dir>/showcase-<theme>.png   the three screens side by side

The frame is drawn, not a mockup image: titanium shell with near-square
corners, thin black bezel, centred punch-hole camera, side keys. All sizes
scale with the screenshot, so 1080x2340 and 1440x3120 both work.

Usage: python scripts/frame_screenshots.py <raw_dir> docs/screenshots
Needs Pillow (`pip install pillow`).
"""
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

SCREENS = ["home", "history", "profile"]
THEMES = ["light", "dark"]

PHONE_WIDTH = 600   # framed single-phone output width (px)
GAP = 48            # space between phones in the showcase (px)

# Proportions relative to the screenshot width.
BEZEL = 0.022        # black border around the display
SHELL = 0.014        # visible titanium rim outside the bezel
SCREEN_RADIUS = 0.035
CAMERA = 0.030       # punch-hole diameter
CAMERA_TOP = 0.030   # punch-hole centre distance from display top

TITANIUM = (74, 74, 78)
TITANIUM_HI = (150, 150, 156)
BLACK = (8, 8, 10)


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def frame(shot):
    shot = shot.convert("RGB")
    w, h = shot.size
    bezel, shell = round(w * BEZEL), round(w * SHELL)
    key_depth = max(2, round(w * 0.006))
    screen_r = round(w * SCREEN_RADIUS)
    body_w, body_h = w + 2 * (bezel + shell), h + 2 * (bezel + shell)
    body_r = screen_r + bezel + shell // 2  # S24 Ultra: corners only slightly rounder than the display
    margin = round(w * 0.06)                # room for the shadow and side keys
    canvas = Image.new("RGBA", (body_w + 2 * margin, body_h + 2 * margin), (0, 0, 0, 0))
    ox, oy = margin, margin

    # Drop shadow.
    shadow = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(shadow).rounded_rectangle(
        (ox, oy + margin // 4, ox + body_w, oy + body_h + margin // 4), body_r, fill=110)
    shadow = shadow.filter(ImageFilter.GaussianBlur(margin / 3))
    canvas.paste((0, 0, 0, 255), (0, 0), shadow)

    d = ImageDraw.Draw(canvas)
    # Side keys on the right edge: volume rocker, then power.
    for top, length in ((0.20, 0.11), (0.35, 0.055)):
        y0 = oy + round(body_h * top)
        d.rounded_rectangle((ox + body_w - key_depth, y0, ox + body_w + key_depth, y0 + round(body_h * length)),
                            key_depth, fill=TITANIUM)

    # Titanium shell with a thin highlight ring, then the black glass.
    d.rounded_rectangle((ox, oy, ox + body_w - 1, oy + body_h - 1), body_r, fill=TITANIUM_HI)
    edge = max(2, shell // 4)
    d.rounded_rectangle((ox + edge, oy + edge, ox + body_w - 1 - edge, oy + body_h - 1 - edge),
                        body_r - edge, fill=TITANIUM)
    d.rounded_rectangle((ox + shell, oy + shell, ox + body_w - 1 - shell, oy + body_h - 1 - shell),
                        body_r - shell, fill=BLACK)

    # Display.
    sx, sy = ox + shell + bezel, oy + shell + bezel
    canvas.paste(shot, (sx, sy), rounded_mask(shot.size, screen_r))

    # Punch-hole camera.
    cam_r = round(w * CAMERA / 2)
    cx, cy = sx + w // 2, sy + round(w * CAMERA_TOP)
    d.ellipse((cx - cam_r, cy - cam_r, cx + cam_r, cy + cam_r), fill=BLACK)
    lens = max(1, cam_r // 3)
    d.ellipse((cx - lens, cy - lens, cx + lens, cy + lens), fill=(24, 26, 34))

    # Trim to content and downscale.
    canvas = canvas.crop(canvas.getbbox())
    scale = PHONE_WIDTH / canvas.width
    return canvas.resize((PHONE_WIDTH, round(canvas.height * scale)), Image.LANCZOS)


def showcase(phones):
    w = sum(p.width for p in phones) + GAP * (len(phones) - 1)
    h = max(p.height for p in phones)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    x = 0
    for p in phones:
        out.alpha_composite(p, (x, h - p.height))
        x += p.width + GAP
    return out


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    raw_dir, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    for theme in THEMES:
        phones = []
        for screen in SCREENS:
            src = raw_dir / f"{screen}-{theme}.png"
            if not src.exists():
                print(f"skip {src} (missing)")
                continue
            framed = frame(Image.open(src))
            framed.save(out_dir / f"{screen}-{theme}.png", optimize=True)
            phones.append(framed)
            print(f"wrote {out_dir / f'{screen}-{theme}.png'}")
        if phones:
            showcase(phones).save(out_dir / f"showcase-{theme}.png", optimize=True)
            print(f"wrote {out_dir / f'showcase-{theme}.png'}")


if __name__ == "__main__":
    main()
