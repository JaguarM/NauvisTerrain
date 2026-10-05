Factorio
========

What Factorio 2.0.77 gives the terrain: the tools in the install, and what Nauvis's map gen is
made of according to the dump. `NOISE.md` is the expression language; this is the data.

The install
-----------

`F:\Steam\steamapps\common\Factorio`, the base game only. `reference/README.md` lists the files
worth reading in it.

- `factorio.exe --dump-data` writes every prototype as JSON (13 MB) to
  `%APPDATA%\Factorio\script-output\data-raw-dump.json` and exits in a second; the copy here is
  `reference/factorio/data-raw-2.0.77.json`.
- `python tools/factorio_docs.py` writes the API pages the terrain needs as text into
  `reference/factorio/docs/`, one file per page.

The map preview
---------------

`factorio.exe --generate-map-preview <file.png | dir/>` draws Nauvis without starting a game: one
tile per pixel, north up, ores, trees, rocks, cliffs and enemies drawn over the tiles. 512 by 512
takes 0.05 seconds. There is no scale option in 2.0.

| | |
|---|---|
| `--map-gen-seed SEED` | the seed; `--map-gen-seed-max SEED` adds every second seed up to it |
| `--generate-map-preview-random COUNT` | that many random seeds |
| `--map-preview-size SIZE` | pixels a side, default 1024 |
| `--map-preview-offset X,Y` | the centre, in tiles, default 0,0 |
| `--map-gen-settings FILE` | the sliders; `data/map-gen-settings.example.json` in the install is the format |
| `--report-quantities iron-ore,...` | approximate amounts of the named prototypes |
| `--threads N` | |

`reference/factorio/previews/nauvis-123-512.png` is seed 123.

Probing a running game
----------------------

`LuaSurface.calculate_tile_properties(property_names, positions)` returns the value of any named
property, every named noise expression included, at a list of positions; unknown names are
ignored. `helpers.write_file` writes into `script-output`. A mod that defines its own named
expressions (`basis_noise{...}` with chosen parameters, say) can sample them this way.

`tools/oracle.py` does that without a window: it writes such a mod and a config whose write-data
is `build/oracle/`, and `factorio.exe --config ... --create map.zip --map-gen-seed N` runs the
mod's `on_init`, which samples and writes, in about two seconds. The game's own settings, mods and
saves are not touched. `calculate_tile_properties` evaluates its list as one batch, on the list
path (`NOISE.md`). An area job generates the chunks over an area and reads back their tiles,
entities and decoratives, the only window onto the grid path and the passes after it. A probe's own
data-stage Lua can change prototypes: made-up tile autoplace expressions show where one expression
is above another, and a resource on every tile whose richness is an expression times 2^24 reads
that expression's value in the chunk's own batch. `helpers.table_to_json` writes infinities as bare
`inf` and -0.0 as `-0`, which JSON reads as the integer 0; the oracle reads both back as Factorio
meant them. `tools/compare.py` sets Factorio's values and chunks beside ours.

What the oracle checks
----------------------

- Every root of the program, the climate and every tile's, entity's and decorative's probability
  and richness, is Factorio's bit for bit over 2311 positions for seeds 123 and 987654321.
  `core/src/test/resources/factorio-values.json` holds 30 positions of it.
- Generated chunks around the start, -64 to 63 both ways, which Factorio makes with the map: every
  tile, entity and decorative is Factorio's, 16384 tiles, 1034 entities and 6214 decoratives on seed
  123, 16384, 1468 and 7585 on 987654321. `core/src/test/resources/factorio-chunks.json` holds the 64 by
  64 tiles in the middle on seed 123, and `EvaluatorTest` holds `Terrain` to both fixtures.
- Far from the start, at 640 and at -900, -700, 128 by 128 tiles: every tile is Factorio's. With
  Factorio's enemies and cliffs off (`--bare`), so are the decoratives; the trees and rocks differ
  only within a few tiles of chunk edges, where Factorio's own order of making chunks decides
  (`GAPS.md`).
- Made-up tile expressions (`x - y` against 0, `x / 64` against `y / 64`) place every one of 16384
  tiles as the highest probability, ties going to `grass-1` over `grass-2`, and a
  `multioctave_noise` with `offset_x = 1000` agrees with the grid path, not the list path.

Nauvis's map gen settings
-------------------------

`planet.nauvis.map_gen_settings` in the dump:

- `aux_climate_control` and `moisture_climate_control` are on; `property_expression_names` is
  empty, so the global `elevation` is used, which is `elevation_nauvis`. Likewise `moisture` is
  `moisture_nauvis`, `aux` `aux_nauvis`, `temperature` `temperature_basic`, `cliff_elevation`
  `cliff_elevation_nauvis`, `cliffiness` `cliffiness_nauvis`.
- `cliff_settings`: the prototype `cliff`, the control `nauvis_cliff`, `cliff_smoothing` 0. Not
  set, so Factorio's defaults: `cliff_elevation_0` 10, `cliff_elevation_interval` 40.
- Twelve autoplace controls. Resource, each with richness: `iron-ore`, `copper-ore`, `stone`,
  `coal`, `crude-oil`, `uranium-ore`. Terrain: `water`, `trees`, `rocks`,
  `starting_area_moisture`. Cliff: `nauvis_cliff`. Enemy: `enemy-base`.
- `autoplace_settings` names 21 tiles, 34 decoratives and 10 entities: the six resources, `fish`,
  `huge-rock`, `big-rock`, `big-sand-rock`. The twenty trees, two spawners and four worms are not
  named; they run because their `default_enabled` is not false.

What Nauvis places
------------------

| | |
|---|---|
| Ground | `grass-1` to `4`, `dry-dirt`, `dirt-1` to `7`, `sand-1` to `3`, `red-desert-0` to `3`. Each is `expression_in_range_base(aux_from, moisture_from, aux_to, moisture_to) + noise_layer_noise(n)`, some the `max` of two ranges; `sand-1` also ranges on elevation near water |
| Water | `water` is `water_base(0, 100)`, `deepwater` `water_base(-2, 200)` |
| Trees | `tree-01` to `tree-09` with `-red` and `-brown` variants, `dry-tree`, `dead-tree-desert`, `dead-grey-trunk`, `dead-dry-hairy-tree`, `dry-hairy-tree`. Control `trees`; probability a named expression of the same name (`tree_01`); richness `clamp(random_penalty_at(6, 1), 0, 1)`. Collision 0.8 by 0.8, mined in 0.55 seconds for 4 wood; `dry-tree` 0.8 by 1, 0.5 seconds for 4; the other four 1.2 by 1.2, 0.5 seconds for 2 |
| Rocks | `huge-rock` (3 by 2.2, mined in 3 s for 24 to 50 stone and 24 to 50 coal), `big-rock` (2 by 1.9, 2 s, 20 stone), `big-sand-rock` (1.5 by 1.5, 2 s, 19 to 25 stone). Control `rocks` |
| Decoratives | 34. Plants up to 4 by 4 tiles (the carpet grasses); five small rocks on the `rocks` control (`medium-rock`, `small-rock`, `tiny-rock`, `medium-sand-rock`, `small-sand-rock`); six decals, mud and sand, up to 15 by 12 tiles. Nine have `placement_density` 2 |
| Ores | `iron-ore`, `copper-ore`, `coal`, `stone`, mined in a second each; uranium and oil are not placed here. Probability and richness come from the noise function `resource_autoplace_all_patches`, whose local functions build the patches from `spot_noise` |
| Fish | probability a constant 0.01 |
| Cliffs | the prototype `cliff`: a 4 by 4 grid offset by (0, 0.5), twenty orientations, not minable, removed by `cliff-explosives`. Placed where `cliff_elevation` crosses `cliff_elevation_0 + k · cliff_elevation_interval` and `cliffiness` is above 0.5; continuity (`cliff_richness`) above 1 makes longer unbroken walls, below 1 larger gaps |

`map_color`, the preview's colour of each, is 0 to 255 for tiles, 0 to 1 for resources, and
`{r, g, b}` for the cliff; trees have none and are charted in
`utility-constants.default.chart.default_color_by_type.tree` (0.19, 0.39, 0.19, alpha 0.4).

Each tile also has `absorptions_per_second.pollution`: grass and dirt 1.8e-5, sand and red desert
1.5e-5, water 2.5e-5.

Named expressions and functions
-------------------------------

92 `noise-expression` and 28 `noise-function` prototypes. Two expressions are numbers, not
strings (`default_regular_resource_patch_set_count`, `default_starting_resource_patch_set_count`).
Seven carry `local_expressions`, one `local_functions`; the longest expression is 654 characters.

The functions: `lerp`, `slider_to_linear`, `slider_rescale`, `spot_at_angle`,
`starting_spot_at_angle`, `rotate_x`, `rotate_y`, `noise_layer_noise`, `random`,
`random_penalty_at`, `random_penalty_between`, `random_penalty_inverse`, `range_select`,
`range_select_base`, `asymmetric_ramps`, `place_every_n`, `quick_multioctave_noise_persistence`,
`amplitude_corrected_multioctave_noise`, `resource_autoplace_all_patches`,
`make_0_12like_lakes`, `finish_elevation`, `elevation_nauvis_function`, `normalize`,
`enemy_autoplace_base`, `expression_in_range_base`, `water_base`, `rpi`, `decorative_mix_noise`.

What each part of the world reaches, counted by a name scan (the generator gives exact numbers):

| | Named expressions and functions | Built-ins |
|---|---|---|
| Tiles | 35 | `basis_noise`, `multioctave_noise`, `quick_multioctave_noise`, `variable_persistence_multioctave_noise`, `distance_from_nearest_point`, `expression_in_range` |
| Trees | 43 | `basis_noise`, `multioctave_noise`, `quick_multioctave_noise`, `distance_from_nearest_point`, `random_penalty` |
| Decoratives | 36 | the same as trees |
| Rocks | 24 | `basis_noise`, `multioctave_noise`, `quick_multioctave_noise`, `distance_from_nearest_point` |
| Cliffs | 25 | the tiles' noise, without `expression_in_range` |
| Ores | 33 | the tiles' noise, `random_penalty`, `spot_noise` |

Factorio's own compiler, in the example log of its docs (settings unknown): the tile program is
1034 expressions, 456 unique, 411 operations; the entity program 1249 operations; the cliff
program 54. A yardstick for the generator's merged node counts.

Presets
-------

`map-gen-presets.default` in the dump:

| | |
|---|---|
| `default` | |
| `rich-resources` | every ore's richness `very-good` |
| `marathon` | technology costs four times; the map is the default |
| `death-world`, `death-world-marathon` | enemies `very-high` and `very-big`, a small starting area |
| `rail-world` | ores at frequency 1/3 and size 3, water at frequency 0.5 and size 1.5 |
| `ribbon-world` | `elevation_lakes`, ores at frequency 3, size 0.5, richness 2, water at frequency 4 and size 0.25, cliffs at frequency 0.25 and size 0.75, starting area 3, and a map 128 tiles tall |
| `lakes` | `elevation_lakes`, `moisture_basic`, `aux_basic`, `cliffiness_basic`, `cliff_elevation_from_elevation`, cliff smoothing 1, trees at size 0.5, `trees_forest_path_cutout` 1 |
| `island` | the same with `elevation_island` |

A slider value is a number, or a name: `none` 0, `very-low`/`very-small`/`very-poor` 1/2,
`low`/`small`/`poor` 1/√2, `normal`/`medium`/`regular` 1, `high`/`big`/`good` √2,
`very-high`/`very-big`/`very-good` 2. Factorio supports 0 and 1/6 to 6.
