Factorio's noise language
=========================

What the generator parses and the evaluator runs: the part of Factorio's language Nauvis uses, and
how each built-in is made here. Factorio's own pages are listed in `reference/README.md`.

Syntax
------

An expression is a number, a boolean or a string. The string's tokens:

| | |
|---|---|
| identifier | `[a-zA-Z_][a-zA-Z0-9_:]*`, so `control:water:frequency` is one name |
| number | decimal with optional exponent, or `0x` hex |
| string | `"..."` or `'...'`, no escapes |

Operators, tightest first: `^` (right-associative); unary `+ - ~` (right-associative);
`* / % %%`; `+ -`; `< <= > >=`; `== ~= !=`; `&`; `~`; `|`. `%` is `x - floor(x / y) * y`, `%%` is
C's `fmod`. Comparisons give 1 or 0. The bitwise operators work on the value cast to a signed
32-bit integer.

A call is positional, `clamp(x, -1, 1)`, or named, `clamp{value = x, min = -1, max = 1}`, never
both. A name with a hyphen is reached through `var('tile:grass-1:probability')`. There is no
recursion.

A boolean is a number: positive is true, zero and negative are false. `if(c, a, b)` evaluates
both branches.

A name resolves to the innermost of: a function's parameters and the `local_expressions` and
`local_functions` around it; Nauvis's `property_expression_names` (empty); the `noise-expression`
and `noise-function` prototypes; the built-ins.

Inputs
------

Every expression can read `x` and `y`, and these from the map settings:

| | |
|---|---|
| `map_seed`, `map_seed_small`, `map_seed_normalized` | the seed as a uint32, its low 16 bits, and scaled to 0 to 1 |
| `starting_positions` | Factorio's spawn points; here one, at 0,0 |
| `starting_lake_positions` | the engine places them from the starting positions and the seed; the rule is ours |
| `starting_area_radius`, `cliff_elevation_0`, `cliff_elevation_interval`, `cliff_smoothing`, `cliff_richness` | numbers from the settings |
| `control:<name>:frequency` / `size` / `richness` | one per autoplace control (water, trees, rocks, the cliffs, each ore) |
| `control:moisture:frequency` / `bias`, `control:aux:…`, `control:temperature:…` | the climate sliders |
| `tile:<name>:probability`, `entity:<name>:richness` and so on | another prototype's autoplace expression |

Built-ins
---------

What Nauvis's ground, cliffs, trees, rocks, decoratives and four ores reach, and how each is made.

| Built-in | Here |
|---|---|
| `abs`, `min`, `max`, `clamp`, `if`, `floor`, `ceil`, `sqrt`, `log2`, `sin`, `cos`, `atan2` | exact |
| `pow` and `^` | exact. Factorio's is an approximation; a tile on a threshold may differ |
| `basis_noise` | gradient noise, our own hash. Zero wherever `x·input_scale` and `y·input_scale` are integers, as Factorio's is; amplitude and correlation measured from Factorio's output |
| `multioctave_noise` | octaves of `basis_noise` at doubling scales, each `persistence` times as strong as the next larger; spacing and normalisation checked against the oracle |
| `quick_multioctave_noise` | the same with its own `octave_input_scale_multiplier`, `octave_output_scale_multiplier` and `octave_seed0_shift` |
| `variable_persistence_multioctave_noise` | `multioctave_noise` with `persistence` an expression |
| `distance_from_nearest_point` (and `_x`, `_y`) | exact |
| `expression_in_range` | per Factorio's FFF #282, checked against the oracle |
| `random_penalty` | subtracts a value in `[0, amplitude)` from `source` when `source > 0`; our own hash of `x`, `y` and `seed` |
| `spot_noise` | the documented algorithm with our own candidate points: regions of `region_size`, candidates spaced by `suggested_minimum_candidate_point_spacing`, cones of `3 · quantity / (π · radius²)` peak, a region filled to its target quantity |

Not reached by Nauvis's terrain and so not written: `voronoi_*`, `multisample`, `terrace`, `ridge`,
`pow_precise`. `noise_layer_id` is how a string `seed1` becomes a number: CRC32.

What our noise has to share with Factorio's
-------------------------------------------

- The same `seed0` and `seed1` give the same field everywhere. Expressions lean on it: two tiles
  reading one layer stay correlated.
- Its statistics: the value distribution and the feature size at a given `input_scale`. The
  tiles, trees and cliffs are thresholds on these values, so a noise that swings wider moves every
  boundary. The oracle measures Factorio's.
- `spot_noise` calls with the same `seed0`, `seed1`, `region_size` and
  `suggested_minimum_candidate_point_spacing` draw one series of candidate points, and
  `skip_offset` and `skip_span` deal it out between them. That is what keeps the ores apart.
- Numbers are `float`.
