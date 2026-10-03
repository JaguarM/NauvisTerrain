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

Noise
-----

- Two calls with the same `seed0` and `seed1` must return the same field. A noise keyed on the
  call site rather than the seeds breaks every expression that reads one layer twice.
- A `basis_noise` whose amplitude or feature size differs from Factorio's still makes plausible
  terrain, just with the wrong share of each tile. Compare tile counts with Factorio's preview,
  not only the picture.
- Every `spot_noise` sharing a seed pair, region size and spacing draws from one series of
  candidate points. Give each ore its own series and the patches overlap.
- A sample of a few seeds says little about a noise with features thousands of tiles wide:
  `moisture_noise`'s coarsest octave is 6912 tiles across. Calibrate over many seeds and a wide
  grid, or chase sampling error.
- Regions anchored at 0,0 instead of centred on it split the starting area four ways, and each
  starting ore becomes a swarm of weak blobs.
