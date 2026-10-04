Silent failures
===============

Ways of getting the world wrong that compile, run and look plausible. Read before writing.

Reading Factorio
----------------

- Factorio's y grows south, as Minecraft's z does: (x, y) is (x, z) with no sign. A flipped axis
  mirrors the map and nothing complains; Factorio's preview is drawn north up, so compare.
- Trees and enemies are named nowhere in Nauvis's `autoplace_settings`. They are on because their
  `default_enabled` is not false. A generator that reads only the settings makes a treeless Nauvis.
- `my-noise` is three names and two subtractions. A hyphenated name only parses through `var()`.
- A name resolves to the innermost definition first: a local expression shadows the global one of
  the same name, and a function parameter shadows both.
- A boolean is "positive", not "nonzero": `if(-0.5, a, b)` is `b`.
- Numbers in the dump come as JSON numbers, booleans and strings; all three are expressions.
- `map_color` comes as 0 to 255 for tiles, 0 to 1 for resources, and as an `{r, g, b}` table for
  the cliff.
- A tile is sampled at its corner, not its centre. Sampling centres moves a few percent of
  boundaries and still looks right.
- Python's `id()` of a freed object is handed to the next one. A memo keyed on `id(scope)` answered
  stone's `regular_density_at` with iron's; key on the object, which keeps it alive.

Other mods
----------

- In development a mod runs from two folders, and Dynamic Trees reads `trees/` only from the
  first, the classes: `devTreePack` copies it there. A treepack that works in the jar does
  nothing in `runClient` without it.
- Dynamic Trees cancels a tree only inside a `minecraft:random_selector`; a bare `minecraft:tree`
  feature in a biome's list is left standing.
- Distant Horizons 3.3.3 crashes a gametest server, taking it for a dedicated one; the gametest
  run has its own `run/gametest`, without the mods dropped into `run/mods`.

Noise
-----

- Every number is a float, one operation at a time, folded constants too. A value the map settings
  fix, computed in double, is an ulp off and moves a tile on a threshold.
- A literal is a double until an operation folds it. Round `seed1 = 'tree-01'`'s CRC32 or an
  `expression_in_range` bound to a float and the noise gets other tables, the range other edges.
- `pow` at run time is fastapprox; `pow` between constants is exact. `3 ^ 0.3` and `x ^ 0.3` at
  `x = 3` differ in the seventh digit, and both are Factorio's.
- `min` and `max` give the later of two equal values, so their arguments keep Factorio's order: -0.0
  and 0.0 differ in sign. The generator sorts only operations whose order cannot show.
- A chunk and a list of the same positions are different batches. Over a chunk, noise reading `x`
  and `y` as they are takes the grid path, and `multioctave_noise` scales its offsets with each
  octave there; `random_penalty` and `spot_noise` depend on the batch's first position and bounds.
  A block that is not Factorio's chunk, 32 by 32 from a multiple of 32 in rows of x, gives other
  values that still look right.
- `calculate_tile_properties` evaluates a list, so the oracle's values check only the list path.
  Generated chunks are the check of the grid path, and they pass through Factorio's tile
  correction first.
- One batch with positions 200,000 tiles apart makes `spot_noise` visit every region between them:
  minutes, in Factorio as here. Keep a probe's positions together.
- Every seed below 342 seeds Factorio's random generator alike: those maps share their noise and
  their starting lake. Two small seeds make the same ground.
- `helpers.table_to_json` writes -0.0 as `-0`, which JSON reads as the integer 0. A comparison
  through plain `json.loads` sees two different zeros as one.
- `x ^ -2` written so crashes Factorio's compiler ("Unknown enum value: 88"); a probe writes
  `x ^ (0 - 2)`.
- Every `spot_noise` sharing a seed pair, region size and spacing draws from one series of
  candidate points, and `skip_offset` and `skip_span` deal it out. That is what keeps the ores
  apart.
