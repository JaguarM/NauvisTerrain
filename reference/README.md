Reference
=========

Other people's work, read here and never committed.

`factorio/data-raw-<version>.json` is Factorio's own `data.raw` as JSON, every prototype keyed by
name, from

    "F:/Steam/steamapps/common/Factorio/bin/x64/factorio.exe" --dump-data

which writes `%APPDATA%/Factorio/script-output/data-raw-dump.json` and exits. Copy it here under
the version in `data/base/info.json`. Only the mods in `mod-list.json` are in the dump; this mod
wants `base` alone.

`factorio/docs/` is the API pages the terrain needs, as text: `python tools/factorio_docs.py`.
`factorio/previews/` holds Factorio's own map previews (`docs/FACTORIO.md`, the map preview).

The rest is read where it is installed, under `F:/Steam/steamapps/common/Factorio`:

| | |
|---|---|
| `doc-html/auxiliary/noise-expressions.html` | every built-in variable, constant and function |
| `doc-html/types/NoiseExpression.html` | the expression syntax |
| `data/core/prototypes/noise-programs.lua`, `noise-functions.lua` | the shared expressions, with Wube's comments |
| `data/base/prototypes/noise-expressions.lua` | trees, rocks, decoratives, enemies |
| `data/base/prototypes/planet/planet-map-gen.lua` | Nauvis's map gen settings |
| `data/core/lualib/resource-autoplace.lua` | how ore patches are built from `spot_noise` |
| `data/base/prototypes/map-gen-presets.lua` | the presets: rich resources, lakes, island and the rest |
| `data/base/graphics/terrain/` | the tiles, water and cliffs |
| `data/base/graphics/decorative/` | rocks and decoratives |
| `data/base/graphics/entity/tree/`, `entity/<ore>/` | trees and ore patches |
