#!/usr/bin/env python3
"""Generate the xyzterm pixel-art launcher icon set.

Draws a CRT terminal window with a glowing green prompt on a dark
gradient. Pure pixel art: 12x12 logical grid, nearest-neighbour upscale,
zero anti-aliasing. Regenerates every mipmap PNG at its native size.
"""
from pathlib import Path
from PIL import Image, ImageDraw

RES = Path(__file__).resolve().parent.parent / "core" / "main" / "src" / "main" / "res"

# ── palette ────────────────────────────────────────────────────────────
BG_TOP = (10, 14, 26)
BG_BOT = (20, 26, 46)
BEZEL = (30, 42, 58)        # CRT frame
FRAME = (18, 24, 34)        # window border
SCREEN_BG = (6, 12, 10)     # dark phosphor off
GREEN = (57, 255, 136)      # phosphor green
GREEN_DIM = (38, 176, 96)   # phosphor shade
AMBER = (255, 209, 84)      # status LED

# 12x12 logical canvas. '#'=bezel, '|'=frame, '.'=screen, 'B'=blink block
GRID = [
    "############",
    "#||||||||||#",
    "#|........|#",
    "#|........|#",
    "#|........|#",
    "#|........|#",
    "#|..B>_....|#",
    "#|........|#",
    "#|........|#",
    "#|........|#",
    "#||||||||||#",
    "############",
]

BLOCK = 36  # 432 / 12, powers every density from one logical grid


def logical_art():
    """Render the 12x12 grid at one pixel per cell → base canvas."""
    img = Image.new("RGBA", (len(GRID[0]), len(GRID)), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for y, row in enumerate(GRID):
        for x, ch in enumerate(row):
            if ch == "#":
                d.point((x, y), fill=BEZEL)
            elif ch == "|":
                d.point((x, y), fill=FRAME)
            elif ch == ".":
                d.point((x, y), fill=SCREEN_BG)
            elif ch == "B":
                d.point((x, y), fill=GREEN)
            elif ch == ">":
                d.point((x, y), fill=GREEN_DIM)
            elif ch == "_":
                d.point((x, y), fill=GREEN)
    return img


def render(cells: int, scale: int) -> Image:
    """Upscale a cells×cells canvas by integer scale, no smoothing."""
    img = logical_art().resize((cells, cells), Image.NEAREST)
    if scale > 1:
        img = img.resize((cells * scale, cells * scale), Image.NEAREST)
    return img


def gradient(size: int, top, bottom):
    base = Image.new("RGB", (size, size))
    d = ImageDraw.Draw(base)
    for y in range(size):
        t = y / max(size - 1, 1)
        d.line(
            [(0, y), (size, y)],
            fill=tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)),
        )
    return base


SIZES = {  # density → (background, foreground, legacy, monochrome)
    "mdpi": (108, 108, 48, 108),
    "hdpi": (162, 162, 72, 162),
    "xhdpi": (216, 216, 96, 216),
    "xxhdpi": (324, 324, 144, 324),
    "xxxhdpi": (432, 432, 192, 432),
}

for density, (bg_sz, fg_sz, leg_sz, mono_sz) in SIZES.items():
    d = RES / f"mipmap-{density}"
    d.mkdir(parents=True, exist_ok=True)

    # background: vertical gradient
    grad = gradient(bg_sz, BG_TOP, BG_BOT)
    grad.save(d / "ic_launcher_background.png")

    # foreground: 12-grid scaled so art ≈ 62% of canvas → safely inside
    # the adaptive-icon safe zone (~66% of the full-bleed canvas)
    art_size = round(fg_sz * 0.62)
    art = logical_art().resize((art_size, art_size), Image.NEAREST)
    canvas = Image.new("RGBA", (fg_sz, fg_sz), (0, 0, 0, 0))
    offset = (fg_sz - art_size) // 2
    canvas.paste(art, (offset, offset), art)
    canvas.save(d / "ic_launcher_foreground.png")

    # monochrome: white prompt glyph on transparent
    mono = Image.new("RGBA", (mono_sz, mono_sz), (0, 0, 0, 0))
    glyph = logical_art().convert("RGBA")
    # keep only green cells as white
    px = glyph.load()
    w, h = glyph.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if (r, g, b) == GREEN:
                px[x, y] = (255, 255, 255, 255)
            elif (r, g, b) in (GREEN_DIM, SCREEN_BG, FRAME, BEZEL):
                px[x, y] = (0, 0, 0, 0)
    glyph_size = round(mono_sz * 0.62)
    glyph = glyph.resize((glyph_size, glyph_size), Image.NEAREST)
    offset = (mono_sz - glyph_size) // 2
    mono.paste(glyph, (offset, offset), glyph)
    mono.save(d / "ic_launcher_monochrome.png")

    # legacy launcher: art centered on gradient, with rounded mask
    leg = Image.new("RGBA", (leg_sz, leg_sz))
    leg.paste(gradient(leg_sz, BG_TOP, BG_BOT), (0, 0))
    art_size = round(leg_sz * 0.72)
    art = logical_art().resize((art_size, art_size), Image.NEAREST)
    offset = (leg_sz - art.width) // 2
    leg.paste(art, (offset, offset), art)
    # rounded-corner mask
    mask = Image.new("L", (leg_sz, leg_sz), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([0, 0, leg_sz - 1, leg_sz - 1], radius=leg_sz // 5, fill=255)
    out = Image.new("RGBA", (leg_sz, leg_sz), (0, 0, 0, 0))
    out.paste(leg, (0, 0), mask)
    out.save(d / "ic_launcher.png")

print("done")
