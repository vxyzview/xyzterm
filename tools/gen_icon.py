#!/usr/bin/env python3
"""Generate the xyzterm pixel-art launcher icon set.

A precision CRT terminal window: 2px bezel, 1px frame, 18x18 screen,
glowing green prompt (blink block + chevron + underscore) and an amber
status LED on the bezel. Pure pixel art - 24x24 logical grid, integer
cell scaling, nearest-neighbour upscale, zero anti-aliasing.
"""
from pathlib import Path
from PIL import Image, ImageDraw

RES = Path(__file__).resolve().parent.parent / "core" / "main" / "src" / "main" / "res"

# ── palette ────────────────────────────────────────────────────────────
BG_TOP = (10, 14, 26)
BG_BOT = (20, 26, 46)
BEZEL = (30, 42, 58)        # CRT frame
FRAME = (22, 29, 40)        # window border (deeper than bezel, reads as inset)
SCREEN_BG = (6, 12, 10)     # dark phosphor off
GREEN = (57, 255, 136)      # phosphor green
GREEN_DIM = (41, 178, 98)   # phosphor shade (chevron)
AMBER = (255, 209, 84)      # status LED

N = 24  # logical grid (one cell per unit)


def logical_art():
    """Render the 24x24 grid at one pixel per cell → base canvas."""
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 2px bezel ring
    d.rectangle([0, 0, N - 1, N - 1], fill=BEZEL)
    # 1px window frame ring
    d.rectangle([2, 2, N - 3, N - 3], fill=FRAME)
    # screen well (18x18 interior)
    d.rectangle([3, 3, N - 4, N - 4], fill=SCREEN_BG)

    # amber status LED, top-right of the bezel
    d.rectangle([17, 1, 18, 2], fill=AMBER)

    # prompt: blink block + chevron + underscore (2-cell-tall row, rows 9-10)
    d.rectangle([5, 9, 6, 10], fill=GREEN)       # blinking block
    d.rectangle([8, 9, 9, 10], fill=GREEN_DIM)   # chevron
    d.rectangle([11, 9, 12, 10], fill=GREEN)     # underscore

    return img


def render(cells: int, scale: int) -> Image:
    """Upscale a cells×cells canvas by integer scale, no smoothing."""
    img = logical_art()
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


def fit_cell(canvas: int, fill: float = 0.62, min_cells: int = 3):
    """Largest integer cell size so the N-grid stays inside fill% of canvas."""
    cell = int(canvas * fill // N)
    return max(cell, min_cells)


# density → (canvas for adaptive, legacy launcher, monochrome)
SIZES = {
    "mdpi": (108, 48, 108),
    "hdpi": (162, 72, 162),
    "xhdpi": (216, 96, 216),
    "xxhdpi": (324, 144, 324),
    "xxxhdpi": (432, 192, 432),
}

for density, (fg_sz, leg_sz, mono_sz) in SIZES.items():
    d = RES / f"mipmap-{density}"
    d.mkdir(parents=True, exist_ok=True)

    # background: vertical gradient
    gradient(fg_sz, BG_TOP, BG_BOT).save(d / "ic_launcher_background.png")

    # foreground: the same 24-grid at every density, scaled by cell size.
    # The art fills the grid edge-to-edge (bezel at rows/cols 0-1 and 22-23),
    # so sizing the 24-cell grid to fill ~85% of the canvas makes the glyph
    # read large in the drawer. 85% of 108dp = 92dp grid, inside the 66dp
    # safe circle-equivalent mask region the launcher applies.
    art = render(N, fit_cell(fg_sz, fill=0.85))
    canvas = Image.new("RGBA", (fg_sz, fg_sz), (0, 0, 0, 0))
    offset = (fg_sz - art.width) // 2
    canvas.paste(art, (offset, offset), art)
    canvas.save(d / "ic_launcher_foreground.png")

    # monochrome: white prompt glyph (green cells only) on transparent
    mono = Image.new("RGBA", (mono_sz, mono_sz), (0, 0, 0, 0))
    glyph = logical_art().convert("RGBA")
    px = glyph.load()
    for y in range(N):
        for x in range(N):
            r, g, b, a = px[x, y]
            if (r, g, b) in (GREEN, GREEN_DIM):
                px[x, y] = (255, 255, 255, 255)
            else:
                px[x, y] = (0, 0, 0, 0)
    cell = max(fit_cell(mono_sz), 1) // 2  # monochrome art is slimmer
    size = N * max(cell, 1)
    glyph = glyph.resize((size, size), Image.NEAREST)
    offset = (mono_sz - size) // 2
    mono.paste(glyph, (offset, offset), glyph)
    mono.save(d / "ic_launcher_monochrome.png")

    # legacy launcher: art centered on gradient, rounded mask
    leg = Image.new("RGBA", (leg_sz, leg_sz))
    leg.paste(gradient(leg_sz, BG_TOP, BG_BOT), (0, 0))
    art = render(N, fit_cell(leg_sz, fill=0.9))
    offset = (leg_sz - art.width) // 2
    leg.paste(art, (offset, offset), art)
    mask = Image.new("L", (leg_sz, leg_sz), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, leg_sz - 1, leg_sz - 1], radius=leg_sz // 5, fill=255
    )
    out = Image.new("RGBA", (leg_sz, leg_sz), (0, 0, 0, 0))
    out.paste(leg, (0, 0), mask)
    out.save(d / "ic_launcher.png")

print("done")