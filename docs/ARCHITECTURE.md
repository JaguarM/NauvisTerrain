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
reuse each other's work. A noise octave over a batch looks each lattice corner's gradient up once
when the batch spans few corners, as Nauvis's broad noise does. `spot_noise` runs its argument
expressions over its own batch of candidate points. Numbers are `float`, as Factorio's are; a seed stays an exact integer.

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
- Its Customize button opens Factorio's map generator screen: a preset, then each slider of each
  control the program reads, in Factorio's words and steps (17% to 600%, a bias of ±0.5, none for
  a size), the cliffs' frequency and continuity among them. The values are saved in the world's
  generator settings (`NauvisMap`): the cliff interval is 40 over the frequency, as in Factorio,
  and a preset swaps its properties as Factorio's do. The generator lists the sliders and the
  presets in the program, from the dump and Factorio's English.
- The world is flat between cliffs. Land is one height on each terrace; Factorio's `elevation`
  decides only where water is. `water` is vanilla water 3 deep over sand, `deepwater` 8 deep over
  gravel, cut into the ground.
- A Factorio tile is a block of its own, `nauvis_terrain:<tile>`, shovel work that drops itself.
  Its textures are four of Factorio's own variants of the tile at 16 pixels and six colours
  (`tools/make_textures.py`), each block one of the four at one of four turns; the cliff's are
  squares of Factorio's cliff face, made the same way.
  Grass and dirt count as `#minecraft:dirt` and sand and red desert as `#minecraft:sand`, so what
  a player plants grows.
- Terraces: a column's level is `floor((cliff_elevation - cliff_elevation_0) / cliff_elevation_interval)`,
  and each level is 4 blocks above the one below. The lowest terrace is level -1, top block at y
  64; lower ground is on it. On Nauvis almost all land is level -1 or 0: `cliff_elevation` runs
  from -22 to 47 over 99% of the map, the plateaus of `nauvis_hills` being level 0.
- Where `cliffiness` is above 0.5, the step is a sheer face of `nauvis_terrain:cliff`, which no
  tool mines and only bedrock-proof force breaks: what removes a cliff is a pack's, aimed at
  `#nauvis_terrain:cliffs`, as Factorio's cliffs go only to cliff explosives. Where Factorio
  leaves a gap, the land ramps up over the last tenth of the level's span instead, a few blocks
  wide. A cliff's top is still its tile.
- Every pool lies on the lowest terrace, so water is always level and never spills down a step: a
  lake on a plateau is a pool sunk into it. A raised land column is its tile's block from the
  lowest terrace up, so a bank cut into it shows soil and a cliff shows rock.
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
- Trees, rocks and decoratives are features in the biome's vegetation step, one placed feature
  per Factorio prototype under its Factorio name (`nauvis_terrain:tree_08_brown`), each placed by
  `nauvis_terrain:autoplace` exactly where `Terrain` puts that prototype: decals first, then plants,
  rocks and trees, so a tree replaces a plant and never the reverse.
- A living tree is a vanilla `minecraft:tree` feature of vanilla logs and leaves inside a
  `minecraft:random_selector` of one, as vanilla lists its trees, Factorio-sized:
  spruce-like for the conifers (`tree-01`, `tree-02`), oak-like for the broadleaves, its leaves the
  vanilla leaves nearest its colour (cherry for the red ones, dark oak and pale oak for the brown);
  the five dead and dry trees are bare trunks, a forked desert tree and a fallen log.
- The jar carries a Dynamic Trees treepack (`trees/nauvis_terrain/`, generated): every ground tile
  a soil, dirt-like or sand-like, with its rooty look; a canceller for Nauvis's living trees; and
  Dynamic Trees' own grown in their place, in the mix of species Factorio's trees come to over four
  seeds. With Dynamic Trees the trees are spaced by its rules; without it the pack is inert.
- A rock is a mound of stone over the tiles its collision box covers (`nauvis_terrain:boulder`),
  stone and coal ore half and half in the huge rock, as its yield is; the big sand rock is
  sandstone. A plant decorative is the nearest vanilla plant (short and tall grass, fern, bush,
  dry grass, dead bush) and a small rock a block of stone. A decal is vanilla's disk feature
  repainting the ground with the tile nearest it, as wide as the decal's shorter side.
- Fish are the biome's cod, spawning in water as vanilla's do.

What a pack changes
-------------------

Everything through data, nothing through code (CLAUDE.md, rule 4):

- The block for each Factorio tile and ore, the cliff's block and step, and the lowest terrace's
  height are in the world preset's generator settings. A pack overrides the preset to use its
  own.
- What stands for a tree, rock or decorative is its configured feature,
  `nauvis_terrain:<factorio name>`; a pack overrides the JSON, or replaces the placed feature to
  keep Factorio's placement for a feature of its own.
- What a block drops is its loot table; trees, rocks and plants are vanilla blocks with vanilla
  loot.
- The biome takes biome modifiers like any overworld biome.
