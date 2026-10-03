#!/usr/bin/env python3
"""
Factorio's API pages that the terrain is built from, as plain text.

    F:/Steam/steamapps/common/Factorio/doc-html/<page>.html -> reference/factorio/docs/<page>.txt

The install ships its own copy of the API docs, matching its version. Text greps; HTML does not.
"""

from __future__ import annotations

import html
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DOCS = Path("F:/Steam/steamapps/common/Factorio/doc-html")
OUT = REPO / "reference" / "factorio" / "docs"

PAGES = [
    "auxiliary/noise-expressions",
    "types/NoiseExpression",
    "types/NoiseFunction",
    "prototypes/NamedNoiseExpression",
    "prototypes/NamedNoiseFunction",
    "types/AutoplaceSpecification",
    "types/AutoplaceSettings",
    "prototypes/AutoplaceControl",
    "types/FrequencySizeRichness",
    "types/MapGenSize",
    "types/MapGenSettings",
    "types/PlanetPrototypeMapGenSettings",
    "types/CliffPlacementSettings",
    "types/MapGenPreset",
    "prototypes/MapGenPresets",
    "prototypes/PlanetPrototype",
    "prototypes/TilePrototype",
    "prototypes/TreePrototype",
    "prototypes/DecorativePrototype",
    "prototypes/SimpleEntityPrototype",
    "prototypes/ResourceEntityPrototype",
    "prototypes/CliffPrototype",
    "prototypes/FishPrototype",
    "concepts/MapGenSettings",
    "classes/LuaSurface",
    "classes/LuaHelpers",
]

BLOCK_END = re.compile(r"<(br|/p|/div|/h\d|/li|/tr|/pre|/table)[^>]*>")


def to_text(page: str) -> str:
    source = (DOCS / f"{page}.html").read_text(encoding="utf-8")
    source = re.sub(r"<script.*?</script>|<style.*?</style>", "", source, flags=re.S)
    text = html.unescape(re.sub(r"<[^>]+>", "", BLOCK_END.sub("\n", source)))
    text = re.sub(r"\n\s*\n+", "\n", text)
    # The sidebar, every prototype and class name in the API, follows the page's own text.
    cut = text.find("Filter properties")
    return text[:cut] if cut > 0 else text


def main() -> int:
    if not DOCS.is_dir():
        print(f"no Factorio docs at {DOCS}", file=sys.stderr)
        return 1
    OUT.mkdir(parents=True, exist_ok=True)
    for page in PAGES:
        target = OUT / f"{page.replace('/', '-')}.txt"
        target.write_text(to_text(page), encoding="utf-8")
        print(f"{target.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
