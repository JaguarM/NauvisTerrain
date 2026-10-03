Architecture
============

The rules the world is built to. Why a rule is the way it is lives here and nowhere else.

The pipeline
------------

    reference/factorio/data-raw-2.0.77.json ┐
    data/terrain.json                       ┴→ tools/gen_terrain.py → the noise program (committed)
                                                                              ↓
                                                  the noise package (Java) → the chunk generator

The generator is the only thing that reads Factorio's syntax. It parses every expression Nauvis's
map gen reaches, resolves names by Factorio's rules (`NOISE.md`), inlines noise functions, folds
constants and merges identical subexpressions, and writes one graph: numbered nodes, each an
operation on earlier nodes, and named roots for what the world asks: `elevation`, `moisture`,
`aux`, `temperature`, `cliff_elevation`, `cliffiness`, and the probability and richness of
every tile, tree, rock, decorative and ore it places. What the map settings change (`map_seed`,
each `control:<name>:<setting>`, `starting_positions`) stays a named input, so one program serves
every seed and slider. A property a preset swaps through `property_expression_names` (`elevation`
to `elevation_lakes`, say) is one node holding every value the presets give it, and the map
settings pick one. `data/terrain.json` holds the choices the dump cannot make: the planet, and
which prototypes the world does not place. The program is
`src/main/resources/nauvis_terrain/noise/nauvis.json`, and it also carries what the world needs of
each prototype besides its noise: order, placement density, collision box, map colour.

A new Factorio version is a new dump and a regenerated program, never a hand edit.

Evaluation
----------

The program runs over a batch of points at once: each node computes the whole batch before the
next starts, and runs once per batch. A batch is usually a chunk's 256 columns; `spot_noise` runs
its argument expressions over its own batch of candidate points. Numbers are `float`, as
Factorio's are.

The noise package is plain Java (CLAUDE.md, rule 6). A test renders a seed to a PNG, each tile in
its `map_color` from the dump, so it reads like Factorio's map preview of the same seed and
settings. Looking at the two side by side is how the world is judged before anything is built in
Minecraft.

Autoplace
---------

Factorio's rules, from its docs. An autoplace runs on Nauvis when Nauvis's `autoplace_settings`
name it or its `default_enabled` is not false.

- Tiles: of all tile probabilities at a position, the highest wins.
- Trees, rocks, decoratives and ores: the probability is the chance of each of
  `placement_density` attempts on a tile, taken in `order`; an attempt that would overlap
  something already placed fails. An ore's richness is its amount.
- Cliffs: along the contours `cliff_elevation_0 + k · cliff_elevation_interval` of
  `cliff_elevation`, on a 4 by 4 grid, where `cliffiness` is above 0.5. The interval is 40 over the
  cliff control's frequency; continuity (`cliff_richness`) sets how unbroken the lines are.

The world
---------

- Factorio's x and y are Minecraft's x and z: both grow east and south. One tile is one block.
- The world is flat. Land is one height between cliffs; Factorio's `elevation` decides only where
  water is. `water` and `deepwater` are vanilla water at two depths, cut into the ground.
- A cliff is a step of a fixed few blocks between two cliff levels, so land rises in terraces
  where Factorio draws cliffs. Where Factorio leaves a gap in a cliff line, the step is a ramp.
- The top block is the tile's, over stone, deepslate and bedrock. No caves, no aquifers.
- An ore replaces the ground where Factorio puts a resource: iron, copper, coal and stone, as
  vanilla's `iron_ore`, `copper_ore`, `coal_ore` and `stone`.
- The spawn is Factorio's starting position, 0,0.
- One biome, `nauvis_terrain:nauvis`, in `#minecraft:is_overworld` so biome modifiers aimed at
  the overworld reach it. It carries vanilla's underground ores except iron, copper and coal, and
  no surface features.
- The chunk generator runs Minecraft's biome decoration after its own pass, so a feature that a
  biome modifier adds lands here as anywhere in the overworld: vanilla's ores, a pack's oil or
  water.

What a pack changes
-------------------

Everything through data, nothing through code (CLAUDE.md, rule 4):

- The block for each Factorio name, tile, tree, rock, decorative or ore, is in the world preset's
  generator settings. A pack overrides the preset to use its own blocks.
- What a tree or rock drops is its loot table.
- The biome takes biome modifiers like any overworld biome.
