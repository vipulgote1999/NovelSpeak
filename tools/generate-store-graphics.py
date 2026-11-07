#!/usr/bin/env python3
"""Generate NovelSpeak launcher icons and Play Store listing graphics.

Run from the repository root:
    python3 tools/generate-store-graphics.py

Outputs are deterministic (no random noise), so store assets can be regenerated from
the same brand palette used by the app (Palette.kt).
"""
from __future__ import annotations

import os
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "res"
GRAPHICS = ROOT / "docs" / "play-store" / "graphics"

BG = (14, 19, 24, 255)          # Palette.BG
SURFACE = (22, 29, 36, 255)     # Palette.SURFACE
SURFACE_HIGH = (33, 43, 52, 255)
BORDER = (44, 57, 68, 255)
TEXT = (241, 246, 250, 255)     # Palette.TEXT
MUTED = (154, 169, 184, 255)    # Palette.MUTED
ACCENT = (111, 231, 190, 255)   # Palette.ACCENT
AMBER = (244, 198, 106, 255)    # Palette.AMBER
PAPER = (244, 240, 230, 255)

FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_REG = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"


def rounded_mask(size: int, radius: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    return mask


def draw_mark(img: Image.Image, scale: float = 1.0) -> None:
    """Draw the open-book + sound-wave mark into a square image.

    Coordinates are authored on a 1024 grid; `scale` maps them to the target canvas.
    """
    d = ImageDraw.Draw(img)
    s = 1024.0 * scale

    # Book pages. The gap in the middle is the spine.
    left = [(215, 330), (497, 292), (497, 704), (215, 742)]
    right = [(527, 292), (809, 330), (809, 742), (527, 704)]
    d.polygon([(int(x * scale), int(y * scale)) for x, y in left], fill=PAPER)
    d.polygon([(int(x * scale), int(y * scale)) for x, y in right], fill=PAPER)

    # Soft page shading and a mint spine.
    d.line([(497 * scale, 300 * scale), (497 * scale, 704 * scale)], fill=(210, 205, 194, 255), width=int(10 * scale))
    d.line([(527 * scale, 300 * scale), (527 * scale, 704 * scale)], fill=(210, 205, 194, 255), width=int(10 * scale))
    d.line([(512 * scale, 292 * scale), (512 * scale, 728 * scale)], fill=ACCENT, width=int(16 * scale))

    # Page rules.
    for i, y in enumerate((400, 470, 540, 610)):
        inset = 42 + (i % 2) * 8
        d.line([(254 * scale, y * scale), ((480 - inset) * scale, y * scale)], fill=(196, 190, 178, 255), width=int(9 * scale))
        d.line([(544 * scale, y * scale), ((770 - inset) * scale, y * scale)], fill=(196, 190, 178, 255), width=int(9 * scale))

    # Bookmark ribbon on the right page.
    d.polygon(
        [
            (int(720 * scale), int(300 * scale)),
            (int(784 * scale), int(320 * scale)),
            (int(784 * scale), int(430 * scale)),
            (int(752 * scale), int(402 * scale)),
            (int(720 * scale), int(430 * scale)),
        ],
        fill=AMBER,
    )

    # Three sound waves radiating from the book. Thick strokes survive 48 px icons.
    wave_box = (760 * scale, 330 * scale, 1010 * scale, 694 * scale)
    for radius, width in ((100, 22), (150, 20), (200, 18)):
        box = (
            wave_box[0],
            wave_box[1] + (200 - radius) * scale,
            wave_box[2] - (200 - radius) * scale,
            wave_box[3] - (200 - radius) * scale,
        )
        d.arc(box, start=-58, end=58, fill=ACCENT, width=int(width * scale))


def master_icon(size: int = 1024, rounded: bool = True) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if rounded:
        d.rounded_rectangle((0, 0, size - 1, size - 1), radius=int(size * 0.22), fill=BG)
    else:
        d.rectangle((0, 0, size - 1, size - 1), fill=BG)
    # Subtle inner ring keeps the icon legible on dark launchers.
    d.rounded_rectangle(
        (int(size * 0.035), int(size * 0.035), int(size * 0.965), int(size * 0.965)),
        radius=int(size * 0.20),
        outline=(42, 55, 68, 255),
        width=max(2, int(size * 0.006)),
    )
    draw_mark(img, size / 1024.0)
    return img


def save_png(img: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG", optimize=True)


def make_launcher_icons() -> None:
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    master = master_icon(1024, rounded=True)
    for density, px in sizes.items():
        icon = master.resize((px, px), Image.LANCZOS)
        save_png(icon, RES / f"mipmap-{density}" / "ic_launcher.png")

        # Round launcher icon: same artwork inside a circular mask.
        mask = Image.new("L", (px, px), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, px - 1, px - 1), fill=255)
        rounded = master.resize((px, px), Image.LANCZOS)
        rounded.putalpha(mask)
        save_png(rounded, RES / f"mipmap-{density}" / "ic_launcher_round.png")

    # Adaptive icon foreground: transparent, 108 dp at xxxhdpi = 432 px, safe-zone art.
    fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    # The mark is authored on 1024; scale to 0.36 so it fits the 66/108 safe zone.
    mark = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    draw_mark(mark, 1.0)
    bbox = mark.getbbox()
    mark = mark.crop(bbox)
    target = int(432 * 0.62)
    mark = mark.resize((target, target), Image.LANCZOS)
    fg.alpha_composite(mark, ((432 - target) // 2, (432 - target) // 2))
    save_png(fg, RES / "mipmap-xxxhdpi" / "ic_launcher_foreground.png")

    # Adaptive icon XML for API 26+.
    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
    for name in ("ic_launcher", "ic_launcher_round"):
        path = RES / "mipmap-anydpi-v26" / f"{name}.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(adaptive, encoding="utf-8")

    colors = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#0E1318</color>
</resources>
"""
    (RES / "values").mkdir(parents=True, exist_ok=True)
    (RES / "values" / "colors.xml").write_text(colors, encoding="utf-8")

    # Play Store 512 x 512 icon: full-bleed so Play's own mask is the only rounding.
    save_png(master_icon(512, rounded=False), GRAPHICS / "icon-512.png")


def make_feature_graphic() -> None:
    w, h = 1024, 500
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)

    # Background wash.
    for y in range(h):
        t = y / max(1, h - 1)
        r = int(BG[0] + (SURFACE_HIGH[0] - BG[0]) * t)
        g = int(BG[1] + (SURFACE_HIGH[1] - BG[1]) * t)
        b = int(BG[2] + (SURFACE_HIGH[2] - BG[2]) * t)
        d.line((0, y, w, y), fill=(r, g, b))
    d.ellipse((-160, 250, 520, 930), fill=(20, 32, 38))
    d.ellipse((650, -220, 1180, 330), fill=(20, 34, 40))

    # Left copy.
    font_title = ImageFont.truetype(FONT_BOLD, 72)
    font_sub = ImageFont.truetype(FONT_REG, 28)
    font_tag = ImageFont.truetype(FONT_BOLD, 20)
    d.text((72, 92), "NovelSpeak", font=font_title, fill=TEXT)
    d.text((76, 184), "Read web novels aloud.", font=font_sub, fill=TEXT)
    d.text((76, 226), "Optional neural voices. Screen off.", font=font_sub, fill=ACCENT)
    d.text((76, 300), "NO ACCOUNT   ·   NO ADS   ·   NO TELEMETRY", font=font_tag, fill=MUTED)
    d.rounded_rectangle((76, 352, 536, 410), radius=29, fill=(24, 43, 40), outline=ACCENT, width=2)
    d.text((102, 368), "Books & Reference  ·  Text to speech", font=font_tag, fill=ACCENT)

    # Right phone mockup.
    px, py, pw, ph = 620, 52, 300, 430
    d.rounded_rectangle((px, py, px + pw, py + ph), radius=34, fill=(7, 10, 13), outline=BORDER, width=3)
    d.rounded_rectangle((px + 12, py + 12, px + pw - 12, py + ph - 12), radius=24, fill=BG)
    # Status bar.
    d.rounded_rectangle((px + 28, py + 26, px + pw - 28, py + 34), radius=4, fill=SURFACE_HIGH)
    # Reader header.
    d.text((px + 26, py + 54), "Chapter 67", font=ImageFont.truetype(FONT_BOLD, 18), fill=TEXT)
    d.text((px + 26, py + 80), "The harbour was quiet.", font=ImageFont.truetype(FONT_REG, 13), fill=MUTED)
    # Paper reading card.
    d.rounded_rectangle((px + 22, py + 108, px + pw - 22, py + 324), radius=16, fill=PAPER)
    body = [
        "The lanterns moved across the",
        "water like slow, patient stars.",
        "She turned the page and let the",
        "story carry her voice into the",
        "dark. Every sentence arrived",
        "in the same warm, even tone.",
    ]
    fy = py + 132
    for line in body:
        d.text((px + 38, fy), line, font=ImageFont.truetype(FONT_REG, 13), fill=(39, 45, 51))
        fy += 27
    # Playback card.
    d.rounded_rectangle((px + 22, py + 342, px + pw - 22, py + 406), radius=16, fill=SURFACE)
    d.ellipse((px + 36, py + 356, px + 70, py + 390), fill=ACCENT)
    d.polygon([(px + 48, py + 363), (px + 48, py + 383), (px + 62, py + 373)], fill=BG)
    d.text((px + 82, py + 358), "Kokoro · 1.0×", font=ImageFont.truetype(FONT_BOLD, 13), fill=TEXT)
    d.text((px + 82, py + 378), "Playing · 12 / 108", font=ImageFont.truetype(FONT_REG, 11), fill=MUTED)
    # Waveform.
    for i in range(14):
        bh = 8 + (i * 7) % 24
        x = px + 196 + i * 6
        d.rounded_rectangle((x, py + 374 - bh // 2, x + 3, py + 374 + bh // 2), radius=2, fill=ACCENT)

    save_png(img, GRAPHICS / "feature-graphic-1024x500.png")


def main() -> None:
    GRAPHICS.mkdir(parents=True, exist_ok=True)
    make_launcher_icons()
    make_feature_graphic()
    print("Wrote launcher icons to", RES)
    print("Wrote store graphics to", GRAPHICS)


if __name__ == "__main__":
    main()
