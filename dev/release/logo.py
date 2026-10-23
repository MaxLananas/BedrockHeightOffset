#!/usr/bin/env python3
"""Builds the Hauteur logo: the vector source and every raster export in assets/logo/.

The mark is constructed, not painted. It is an H on an 8 x 8 module grid (module = 128 units of a
1024 unit canvas): two paper pillars and one amber crossbar that sits on the module just above the centre line.
The raised crossbar is the mark's idea: the vertical window of the extension, lifted above the
Java-side base. Every edge falls on the module grid, so the mark stays crisp at every export size.

Design rules this follows (see assets/logo/README.md for the sources):
  * one idea, built only from rectangles on a grid (90 degree geometry, two accent colours);
  * the mark is opaque and full-bleed: platforms apply their own corner mask;
  * legible at 32 px: the pillars are 4 px wide at that size;
  * the monochrome variant keeps the same geometry in black, for single-colour contexts.

Usage:  python3 dev/release/logo.py [--out assets/logo]
Requires Python 3.9+ and Pillow (pip install pillow). No ImageMagick, no network.
"""

from __future__ import annotations

import argparse
from pathlib import Path

CANVAS = 1024          # master size, in units
MODULE = 128           # grid module, in units (8 modules per side)
SUPERSAMPLE = 4        # drawing scale before the box-filter downsample
INK = (19, 32, 58)     # #13203A, the ground
PAPER = (244, 241, 234)  # #F4F1EA, the pillars
AMBER = (245, 166, 35)  # #F5A623, the raised crossbar
BLACK = (0, 0, 0)

# (x, y, width, height) in modules, and the role of each part.
PILLAR_LEFT = (2, 2, 1, 4)
PILLAR_RIGHT = (5, 2, 1, 4)
CROSSBAR = (3, 3, 2, 1)  # the module directly above the centre line (pillars span modules 2 to 6)

SIZES = (512, 128, 32)   # colour exports besides the master


def _rect_units(module_rect: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    x, y, w, h = module_rect
    return x * MODULE, y * MODULE, (x + w) * MODULE, (y + h) * MODULE


def svg(mono: bool = False) -> str:
    """Vector source. The colour version has an opaque ground; the mono version has none."""
    ground = "" if mono else f'  <rect width="{CANVAS}" height="{CANVAS}" fill="{_hex(INK)}"/>\n'
    paper = _hex(BLACK if mono else PAPER)
    amber = _hex(BLACK if mono else AMBER)
    parts = []
    for name, rect, fill in (
        ("pillar-left", PILLAR_LEFT, paper),
        ("pillar-right", PILLAR_RIGHT, paper),
        ("crossbar", CROSSBAR, amber),
    ):
        x0, y0, x1, y1 = _rect_units(rect)
        parts.append(
            f'  <rect id="{name}" x="{x0}" y="{y0}" width="{x1 - x0}" height="{y1 - y0}" fill="{fill}"/>\n'
        )
    title = "Hauteur (monochrome)" if mono else "Hauteur"
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {CANVAS} {CANVAS}" '
        f'width="{CANVAS}" height="{CANVAS}">\n'
        f"  <title>{title}</title>\n"
        f"{ground}{''.join(parts)}</svg>\n"
    )


def _hex(rgb: tuple[int, int, int]) -> str:
    return "#%02X%02X%02X" % rgb


def render(size: int, mono: bool = False):
    """Rasterises the mark at `size` px. The mode is RGB for colour and RGBA for mono."""
    from PIL import Image, ImageDraw  # imported here so that --check runs without Pillow

    big = CANVAS * SUPERSAMPLE
    if mono:
        canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    else:
        canvas = Image.new("RGB", (big, big), INK)
    draw = ImageDraw.Draw(canvas)
    paper = BLACK if mono else PAPER
    amber = BLACK if mono else AMBER
    for rect, fill in ((PILLAR_LEFT, paper), (PILLAR_RIGHT, paper), (CROSSBAR, amber)):
        x0, y0, x1, y1 = _rect_units(rect)
        if mono:
            fill = (*fill, 255)
        draw.rectangle([x0 * SUPERSAMPLE, y0 * SUPERSAMPLE, x1 * SUPERSAMPLE - 1, y1 * SUPERSAMPLE - 1], fill=fill)
    # BOX is an area average: exact edge coverage for axis-aligned geometry, with no ringing.
    return canvas.resize((size, size), Image.BOX)


def check(out: Path) -> list[str]:
    """Returns the committed SVG sources that differ from the geometry above (no Pillow needed)."""
    drift = []
    for name, mono in (("hauteur.svg", False), ("hauteur-mono.svg", True)):
        path = out / name
        if not path.is_file() or path.read_text(encoding="utf-8") != svg(mono=mono):
            drift.append(name)
    return drift


def write(out: Path) -> list[Path]:
    from PIL import Image  # noqa: F401 - fail early with a clear import error if Pillow is missing
    out.mkdir(parents=True, exist_ok=True)
    written = []
    (out / "hauteur.svg").write_text(svg(), encoding="utf-8")
    (out / "hauteur-mono.svg").write_text(svg(mono=True), encoding="utf-8")
    written += [out / "hauteur.svg", out / "hauteur-mono.svg"]

    master = render(CANVAS)
    path = out / "hauteur-1024.png"
    master.save(path, optimize=True)
    written.append(path)
    for size in SIZES:
        path = out / f"hauteur-{size}.png"
        render(size).save(path, optimize=True)
        written.append(path)

    mono_master = render(CANVAS, mono=True)
    path = out / "hauteur-mono-1024.png"
    mono_master.save(path, optimize=True)
    written.append(path)
    path = out / "hauteur-mono-512.png"
    render(512, mono=True).save(path, optimize=True)
    written.append(path)
    return written


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default=str(Path(__file__).resolve().parents[2] / "assets" / "logo"))
    parser.add_argument("--check", action="store_true",
                        help="verify the committed SVG sources match the geometry; exit 1 on drift")
    args = parser.parse_args()
    if args.check:
        drift = check(Path(args.out))
        if drift:
            raise SystemExit("logo sources differ from dev/release/logo.py: " + ", ".join(drift)
                             + " (run: python3 dev/release/logo.py)")
        print("logo sources match dev/release/logo.py")
        return
    for path in write(Path(args.out)):
        print(path)


if __name__ == "__main__":
    main()
