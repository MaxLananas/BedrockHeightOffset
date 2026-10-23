# Hauteur logo

## The mark

An **H** for *Hauteur*, built from three rectangles on a grid. The amber crossbar sits one module above
the centre line: the window of the extension, lifted above the base. Nothing else is drawn.

The mark is constructed, not painted: `dev/release/logo.py` holds the geometry and writes every file here.
No generative model and no third-party artwork is involved.

## Construction

Canvas 1024 × 1024 units. Grid module 128 units (8 × 8 grid). Every edge falls on the grid.

| Part | Module rect (x, y, w, h) | Units (x0, y0, x1, y1) | Colour |
|---|---|---|---|
| Left pillar | (2, 2, 1, 4) | 256, 256, 384, 768 | Paper `#F4F1EA` |
| Right pillar | (5, 2, 1, 4) | 640, 256, 768, 768 | Paper `#F4F1EA` |
| Crossbar | (3, 3, 2, 1) | 384, 384, 640, 512 | Amber `#F5A623` |
| Ground (colour only) | full canvas | 0, 0, 1024, 1024 | Ink `#13203A` |

The H is centred on the canvas. The crossbar's lower edge meets the centre line.

## Palette

| Name | Hex | Use | Contrast |
|---|---|---|---|
| Ink | `#13203A` | Ground | Paper on ink 14.4:1; amber on ink 8.0:1 |
| Paper | `#F4F1EA` | Pillars | |
| Amber | `#F5A623` | Crossbar | |

Paper against amber is 1.8:1. That is deliberate: the crossbar is a hue shift, not a tonal one, and the
two colours meet only at the junction. Do not add an outline to "fix" it.

## Files

| File | Use |
|---|---|
| `hauteur.svg` | Vector source, colour. Edit the geometry in `dev/release/logo.py` and regenerate. |
| `hauteur-mono.svg` | Vector source, monochrome (black on transparent). |
| `hauteur-1024.png` | Master, full colour, opaque, full-bleed square. |
| `hauteur-512.png`, `hauteur-128.png`, `hauteur-32.png` | Colour exports for READMEs and listings. |
| `hauteur-mono-1024.png`, `hauteur-mono-512.png` | Monochrome exports (black on transparent) for single-colour contexts. |

The exports are full-bleed squares with no pre-rounded corners. Platforms apply their own corner mask
(Apple HIG). Rounding the corners in the file would double the mask.

## Usage

- Minimum size: 32 px. At 32 px the pillars are 4 px wide and the crossbar is 8 px high.
- Clear space: at least one module (128 units, one eighth of the width) on every side.
- Do not rotate, stretch, outline, shadow, or recolour the mark. Do not move the crossbar: its position
  is the concept.
- For single-colour contexts use the monochrome files. The crossbar then reads as a plain H; the lift is
  visible only in colour.

## Regenerate

```
pip install -r dev/release/requirements.txt
python3 dev/release/logo.py
```

The script needs Python 3.11 or later and the Pillow version pinned in `dev/release/requirements.txt`. It writes the SVG and PNG files above into `assets/logo/`.

## Provenance and trademark

- Designed in this repository from the geometry above, by the project's tooling, under the maintainer's direction.
- No external images, fonts or generative models are used.
- No trademark search has been carried out. A web and GitHub search for Minecraft and Geyser projects
  named Hauteur found no collision, but that search is not exhaustive. Run a formal trademark search before
  using the name or mark outside this project.
