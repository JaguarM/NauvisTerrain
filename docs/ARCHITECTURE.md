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
next starts, and runs once per batch; what the map settings fix is computed once. A batch is a
block of 32 by 32 tiles, Factorio's own size, and `Terrain` keeps the blocks it has made for the
chunks that share them, so threads that make chunks side by side (Distant Horizons' among them)
reuse each other's work. `spot_noise` runs its argument expressions over its own batch of
candidate points. Numbers are `float`, as Factorio's are; a seed stays an exact integer.

The noise package is plain Java (CLAUDE.md, rule 6). `./gradlew test` renders seed 123 to
`build/nauvis-123-512.png`, each tile in its `map_color` from the dump, with each tile's share and
each thing's count beside it in `build/nauvis-123-512.txt`, so it reads like Factorio's map preview
of the same seed and settings. Looking at the two side by side is how the world is judged before
anything is built in Minecraft.

Autoplace
---------

Factorio's rules, from its docs. An autoplace runs on Nauvis when Nauvis's `autoplace_settings`
name it or its `default_enabled` is not false.

- Tiles: of all tile probabilities at a position, the highest wins.
- Trees, rocks, decoratives and ores: the probability is the chance of each of
  `placement_density` attempts on a tile, taken in `order`; of things sharing an order only the
  most probable is tried. A thing cannot stand where its collision layers meet a tile's under its
  box (no tree in water, fish only in it), nor overlap an earlier thing whose layers meet its own
  (`NoiseProgram.Mask`). An ore's richness is its amount.
- Earlier means earlier in order, then in attempt, then by a hash of the tile. A thing gives way
  to every earlier candidate that overlaps it and fits its tiles, whether or not that one stands
  in the end, so a chunk's things are decided from its neighbours' rolls alone.
- Cliffs: along the contours `cliff_elevation_0 + k · cliff_elevation_interval` of
  `cliff_elevation`, on a 4 by 4 grid, where `cliffiness` is above 0.5. The interval is 40 over the
  cliff control's frequency; continuity (`cliff_richness`) sets how unbroken the lines are.

The world
---------

- Factorio's x and y are Minecraft's x and z: both grow east and south. One tile is one block,
  sampled at the block's corner, as Factorio samples a tile.
- The world type is `nauvis_terrain:nauvis` in the world type list. Its map seed is the world
  seed's low 32 bits, Factorio's being 32 bits: seed 123 is the map `./gradlew test` renders.
- The world is flat. Land is one height between cliffs, its top block at y 64; Factorio's
  `elevation` decides only where water is. `water` is vanilla water 3 deep over sand, `deepwater`
  8 deep over gravel, both cut into the ground with their surface level with the land's.
- A Factorio tile is a block of its own, `nauvis_terrain:<tile>`, shovel work that drops itself.
  Grass and dirt count as `#minecraft:dirt` and sand and red desert as `#minecraft:sand`, so what
  a player plants grows.
- A cliff is a step of a fixed few blocks between two cliff levels, so land rises in terraces
  where Factorio draws cliffs. Where Factorio leaves a gap in a cliff line, the step is a ramp.
- The top block is the tile's, over stone, deepslate and bedrock, with vanilla's blends between
  them. No caves, no aquifers.
- An ore replaces the ground where Factorio puts a resource: iron, copper, coal and stone, as
  vanilla's `iron_ore`, `copper_ore`, `coal_ore` and `stone`, one block deep in place of the
  tile's block. The patches are Factorio's, starting ones included.
- The spawn is Factorio's starting position, 0,0: vanilla's spawn search starts there when the
  generator has no climate to search.
- One biome, `nauvis_terrain:nauvis`, in `#minecraft:is_overworld` so biome modifiers aimed at
  the overworld reach it. It carries vanilla's underground ores except iron, copper and coal, the
  amethyst geode, plains' climate and mobs, Factorio's water colour, and no surface features. The
  structures vanilla gives every overworld biome (mineshafts, strongholds, trial chambers) come
  with the tag; the End stays reachable.
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
