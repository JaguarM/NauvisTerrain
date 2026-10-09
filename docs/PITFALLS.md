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
- NeoForge 26.2 no longer reads `neoforge.enabledGameTestNamespaces`: a gametest server runs every
  loaded mod's tests, so a pack's run would run this mod's, whose world is not Nauvis there.
  `GameTests` reads the property itself.

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
  Generated chunks are the check of the grid path. Decoratives read `decorative_mix_noise`, a
  `multioctave_noise` with `offset_y = seed`, so their probabilities in a chunk are another field
  than the points say: only generated chunks check them.
- Factorio's spawners bring decoratives of their own (red croton, red pita, mud decals) and keep
  trees off. Set beside this world, which has no enemies, compare without them: `--bare`.
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

Placement
---------

- A `simple_block` feature places into whatever is there. A plant placed on another's tile replaces
  the lower half of a tall one and leaves its top floating; plants go last, into air only.
- Tiles are chosen in Factorio's order of autoplacers, by order and then name, and a tie keeps
  the earlier. Taking them in the program's order gives other tiles wherever two probabilities
  meet, which with ranges capped at 1 is often.
- The entity and decorative generators are seeded from the chunk alone, not the map seed, and draw
  for every tile with a winner, whatever its probability. Skip a draw for a hopeless tile and every
  later thing in the chunk moves.
- Boxes that only touch collide (`BoundingBox::collide`). A strict overlap test lets a rock stand
  against another and moves the trees around it.
- A chunk's correction writes into its neighbours, and a chunk's entities keep off its neighbours'
  later ones: chunks must be done in one fixed order. The order Minecraft asks for them in changes
  with every player and thread.

Versions
--------

- A change in one version's mod and not the other's makes two worlds, and both versions' tests
  pass. What both should share goes in core; what cannot, goes in both, and `GAPS.md` says where
  they differ.
- A 1.21.1 tree turns the ground under its trunk to dirt unless the block refuses. The tile
  blocks refuse (`TerrainBlocks`); a block a pack puts in a tile's place may not, and then the
  sand under a desert tree becomes a patch of dirt.
- 1.21.1's gametest server keeps its world between runs, so the chunks an older build made are
  loaded, not generated, and a test passes on them. `gameTestWorld` makes the world anew each run,
  and only because it is never up to date: the server's writes into its folder are not its own, so
  Gradle would count it done.
