#!/usr/bin/env python3
"""
The tile and cliff textures, from Factorio's own graphics in Minecraft pixels.

    python tools/make_textures.py             write the textures
    python tools/make_textures.py --preview   also write build/textures-preview.png

A ground tile's texture is one of Factorio's one-tile variants of it, 64 pixels a side, shrunk to
16 and cut down to six colours: enough for a ground's grain, few enough to read as a block. Each
tile has the four variants Factorio draws most, which the block's model turns at random. The
cliff's are squares from the face in Factorio's cliff sheet. Factorio's graphics are read where
they are installed (reference/README.md); the textures are committed.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from PIL import Image

REPO = Path(__file__).resolve().parent.parent
FACTORIO = Path("F:/Steam/steamapps/common/Factorio/data")
PROGRAM = REPO / "core" / "src" / "main" / "resources" / "nauvis_terrain" / "noise" / "nauvis.json"
OUT = REPO / "core" / "src" / "main" / "resources" / "assets" / "nauvis_terrain" / "textures" / "block"
SIZE = 16
COLOURS = 6
VARIANTS = 4
# Factorio's draws a tile at 64 pixels; its one-tile variants are the first row of its sheet.
TILE = 64
# The rock face along the middle of cliff-sides.png, in its own pixels.
CLIFF_SHEET = "base/graphics/terrain/cliffs/cliff-sides.png"
CLIFF_FACE_Y = 560
CLIFF_FACE_X = [64, 448, 960, 1472]


def pixels(image: Image.Image) -> Image.Image:
    """A square of Factorio's art as a 16-pixel texture in six colours, any clear part filled with its own mean."""
    rgba = image.convert("RGBA")
    weight = sum(a for *_, a in rgba.get_flattened_data()) or 1
    mean = tuple(round(sum(p[c] * p[3] for p in rgba.get_flattened_data()) / weight) for c in range(3))
    solid = Image.new("RGBA", rgba.size, mean + (255,))
    solid.alpha_composite(rgba)
    small = solid.convert("RGB").resize((SIZE, SIZE), Image.Resampling.BOX)
    return small.quantize(colors=COLOURS, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).convert("RGB")


def ground_tiles(program: dict) -> list[str]:
    return [p["name"] for p in program["prototypes"]
            if p["kind"] == "tile" and "water_tile" not in p["collision_mask"]["layers"]]


def textures() -> dict[str, Image.Image]:
    program = json.loads(PROGRAM.read_text(encoding="utf-8"))
    raw = json.loads((REPO / "reference" / "factorio" / "data-raw-2.0.77.json").read_text(encoding="utf-8"))
    out = {}
    for name in ground_tiles(program):
        main = next(v for v in raw["tile"][name]["variants"]["main"] if v["size"] == 1)
        sheet = Image.open(FACTORIO / main["picture"].replace("__base__", "base"))
        weights = raw["tile"][name]["variants"]["main"][0].get("weights") or [1] * main["count"]
        best = sorted(range(main["count"]), key=lambda i: -weights[i])[:VARIANTS]
        for k, i in enumerate(sorted(best)):
            square = sheet.crop((i * TILE, main["y"], (i + 1) * TILE, main["y"] + TILE))
            out[f"{name.replace('-', '_')}_{k}"] = pixels(square)
    cliff = Image.open(FACTORIO / CLIFF_SHEET)
    for k, x in enumerate(CLIFF_FACE_X):
        out[f"cliff_{k}"] = pixels(cliff.crop((x, CLIFF_FACE_Y, x + TILE, CLIFF_FACE_Y + TILE)))
    return out


def preview(images: dict[str, Image.Image]) -> Path:
    names = sorted(images)
    columns = VARIANTS * 2
    rows = (len(names) + columns - 1) // columns
    scale = 4
    sheet = Image.new("RGB", (columns * (SIZE * scale + 4), rows * (SIZE * scale + 4)), (255, 255, 255))
    for n, name in enumerate(names):
        tile = images[name].resize((SIZE * scale, SIZE * scale), Image.Resampling.NEAREST)
        sheet.paste(tile, ((n % columns) * (SIZE * scale + 4), (n // columns) * (SIZE * scale + 4)))
    path = REPO / "build" / "textures-preview.png"
    path.parent.mkdir(exist_ok=True)
    sheet.save(path)
    return path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--preview", action="store_true")
    args = parser.parse_args()
    images = textures()
    OUT.mkdir(parents=True, exist_ok=True)
    for name, image in images.items():
        image.save(OUT / f"{name}.png")
    print(f"wrote {len(images)} textures to {OUT.relative_to(REPO)}")
    if args.preview:
        print(f"preview: {preview(images)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
