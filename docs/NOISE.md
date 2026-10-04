Factorio's noise language
=========================

What the generator parses and the evaluator runs: the part of Factorio's language Nauvis uses, and
how Factorio's engine computes each built-in, which the evaluator does bit for bit. Factorio's own
pages are listed in `reference/README.md`; `reference/factorio/engine/` holds what was read from
`factorio.exe` through its PDB.

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
| `starting_lake_positions` | one per start, 75 tiles out in a direction the map seed's own random generator draws, truncated toward zero to a tile, as `MapGenSettings::getStartingLakePositions` places it |
| `starting_area_radius`, `cliff_elevation_0`, `cliff_elevation_interval`, `cliff_smoothing`, `cliff_richness` | numbers from the settings |
| `control:<name>:frequency` / `size` / `richness` | one per autoplace control (water, trees, rocks, the cliffs, each ore) |
| `control:moisture:frequency` / `bias`, `control:aux:…`, `control:temperature:…` | the climate sliders |
| `tile:<name>:probability`, `entity:<name>:richness` and so on | another prototype's autoplace expression |

Built-ins
---------

What Nauvis's ground, cliffs, trees, rocks, decoratives and four ores reach, each as `factorio.exe`
2.0.77 computes it. All arithmetic is `float` unless it says double.

| Built-in | How |
|---|---|
| `+ - * /`, unary `-`, `abs`, `sqrt`, `>`, `>=`, `if` | float, one operation at a time; `if(c, a, b)` is `c > 0 ? a : b` |
| `log2` | in double, to a float |
| `clamp`, `min`, `max` | as the engine's compares: `clamp(v, lo, hi)` is `t = v > lo ? v : lo`, then `t < hi ? t : hi`; `min(a, b)` is `clamp(a, -inf, b)` and `max(a, b)` `clamp(a, b, inf)`, so on equal values the later wins |
| `pow` and `^` | fastapprox: a whole exponent by repeated squaring, any other through Mineiro's fastpow2 and fastlog2; between constants, folded exactly |
| `basis_noise` | gradient noise on the integer lattice of `(x + offset_x) · input_scale`: a corner's gradient is one of 256, each 4.2 long, picked by `p3[X & 255] ^ p2[Y & 255] ^ p1`, the tables shuffled by a taus88 seeded with `seed0 + 7 · (seed1 >> 8)` and `p1` the `seed1 & 255`th of one more shuffle; each corner adds `(g · d) · t³`, `t = 1 - min(d², 1)` |
| `multioctave_noise` | all octaves one field, the finest first, octave `k` moved 17.17 cells in x and at half the last one's scale; the finest's amplitude is set so the squares of all of them sum to `output_scale²`, each coarser one `1/persistence` stronger |
| `quick_multioctave_noise` | one `basis_noise` per octave, scales times the multipliers each time, `seed0` plus `octave_seed0_shift` |
| `variable_persistence_multioctave_noise` | one field: octave `k` of `n` at `input_scale / 2^k`, weighted `2^n · output_scale · persistence^(n-k)`, summed coarsest last |
| `distance_from_nearest_point` | the squared distances, in float, to the points as map positions hold them (1/256), the least of them, and its square root unless it reaches the maximum's square |
| `expression_in_range` | the compiler's expansion: per range `half - |v - middle|`, middle and half-width worked out in double from the bounds, times `peak_multiplier` unless it is 1, at most `peak_maximum` unless that is infinite, the least over the ranges |
| `random_penalty` | one taus88 per batch, seeded from the batch's first position and `seed`; from the last position to the first, each whose source is above 0 takes one draw off it, times `amplitude` |
| `spot_noise` | per square region of `region_size`, centred on its multiples, a taus88 of the region and the seeds draws whole-tile candidates, the squared spacing shrinking by a sixteenth at each clash; every `skip_span`-th from `skip_offset` is evaluated and stably sorted most favourable first; spots are taken until the region's target, its mean density times its area, each cut to fit when `hard_region_target_quantity` and its radius then scaled by fastapprox's cube root of the cut; a spot is a cone of peak `3q / (3.1416 · r²)`, in double, falling through 0 at its radius on to `maximum_spot_basement_radius`, and a position takes the highest cone or the basement |

Not reached by Nauvis and so not run: `voronoi_*`, `multisample`, `terrace`, `ridge`, `pow_precise`,
`floor`, `ceil`, `sin`, `cos`, `atan2`, `%`, `%%`, `<`, `<=`, `==`, `!=`, the bitwise operators and
`distance_from_nearest_point_x` and `_y`. The generator folds them between constants and refuses a
program that reaches one. A string is a number wherever a number is wanted, as a `seed1` takes it
(`noise_layer_noise('sand-decal')`): its CRC32, which is also what `noise_layer_id` returns.

Constants
---------

A literal is a double and stays one: `seed0 = 123456789`, a string's CRC32 and
`expression_in_range`'s bounds keep every digit. An operation on constants folds on the numbers as
floats, to a float, as Factorio's compiler folds it: `16777216 + 1 - 16777216` is 0, and `pow` and
`log2` fold precisely. The map settings are constants of the compiled program and fold alike, and
a constant reaches a noise's parameters as Factorio's constructors convert it: a seed, an octave
count or a region size an unsigned 32-bit integer, a scale or an offset a float.

Seeds
-----

Factorio's random generator starts each of its three words at the seed, or at 341 if the seed is
smaller. Every seed from 0 to 341 makes the same noise fields and the same starting lake, at
(74, 4); only `spot_noise`, which mixes `seed0` into each region's seed, tells them apart. Such
seeds share their ground, cliffs and trees, and differ in their ore patches.

Lists and grids
---------------

A batch of positions is a list or a grid. A grid is how Factorio makes a chunk: 32 by 32 positions
from a multiple of 32, a tile apart, rows of x. Over a grid, a noise whose `x` and `y` are the
inputs themselves takes Factorio's grid path, which is the same lattice rounded differently:
`(i · step + (x0 + offset_x)) · input_scale` for a position, `t = max((1 - dy²) - dx², 0)`, the far
corner the next lattice line, the corners summed `(l0 + l2) + (l1 + l3)`. `multioctave_noise` on
the grid path scales its offsets with each octave, where the list path adds them after scaling:
for an offset of 5000, two different fields. Everything else reads its arguments as a list.
`LuaSurface.calculate_tile_properties` hands Factorio a list.
