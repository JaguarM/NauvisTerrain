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

The program runs over a batch of positions as Factorio's engine runs it (`NOISE.md`): each node
computes the whole batch in float before the next starts, and what the map settings fix is folded
once, as Factorio's compiler folds it. A value depends on its batch: noise over a grid takes
Factorio's grid path, `random_penalty` seeds itself from the batch's first position, and
`spot_noise` reads the regions the batch's bounds reach. So the batch is Factorio's own: a block is
a chunk, 32 by 32 tiles from a multiple of 32, evaluated as a grid, and its values are the ones
Factorio gives that chunk. `Terrain` keeps the blocks it has made for the chunks that share them,
so threads that make chunks side by side (Distant Horizons' among them) reuse each other's work.
`spot_noise` runs its argument expressions over its own batch of candidate points.

The noise package is plain Java (CLAUDE.md, rule 6). `./gradlew test` renders seed 123 to
`build/nauvis-123-512.png`, each tile in its `map_color` from the dump, with each tile's share and
each thing's count beside it in `build/nauvis-123-512.txt`, so it reads like Factorio's map preview
of the same seed and settings. Looking at the two side by side is how the world is judged before
anything is built in Minecraft.

Autoplace
---------

Factorio's map generator, as `factorio.exe` runs it; `reference/factorio/engine/` holds what was
read of it. An autoplace runs on Nauvis when Nauvis's `autoplace_settings` name it or its
`default_enabled` is not false. Autoplacers go by `order`, then name, compared byte by byte, and
those sharing an order compete for a tile.

- Tiles: of all tile probabilities at a position the strictly highest wins, so a tie keeps the
  earlier tile. Then the tile correction (`TileCorrection`): for each chunk, a walk from each of its
  tiles over it and its eight neighbours replaces a tile that would make a one-tile strip, a pinch
  or a forbidden neighbour (deepwater beside land) with the neighbour it fails, or the tile
  between. A chunk's correction can change its neighbours, so the order chunks are corrected in
  counts.
- Trees, rocks, ores and fish: per chunk, one random generator seeded from the chunk alone. Each
  tile goes to the most probable of a group whose layers its tile does not meet; every tile with a
  winner draws `placement_density` times, and each draw below the probability is an attempt. A tree
  or rock stands on a 1/16 grid at a jitter two more draws give, an ore at the tile's centre, and
  none where its map generator box meets a tile's layers. Then the chunk's attempts are made in turn,
  each unless its box touches an entity already there whose layers meet its own: boxes that only
  touch collide. An ore made within 1024 tiles of a start removes the trees on its tile by a chance
  that falls with the distance.
- Decoratives: the same, with a generator of their own, every successful draw adding to the tile's
  amount, the position in the tile a hash of the tile and the name. A chunk's decoratives are made
  before its entities, and kept off only by entities of chunks made before; rocks remove the
  decoratives of the object layer under them.
- Chunk order: the area made with the map, chunks -7 to 6, is corrected and made as Factorio makes it
  at the start of a map, column by column. Every other chunk is corrected as itself and its
  neighbours would be in turn, and made after the neighbours that come earlier in a 2 by 2 pattern
  of chunks, so what a chunk gets never depends on the order Minecraft asks for chunks in.
- Cliffs: along the contours `cliff_elevation_0 + k · cliff_elevation_interval` of
  `cliff_elevation`, on a 4 by 4 grid, where `cliffiness` is above 0.5. The interval is 40 over the
  cliff control's frequency; continuity (`cliff_richness`) sets how unbroken the lines are.

The world
---------

- Factorio's x and y are Minecraft's x and z: both grow east and south. One tile is one block,
  sampled at the block's corner, as Factorio samples a tile.
- The world type is `nauvis_terrain:nauvis` in the world type list, and a new world starts on it:
  whoever installs the mod wants Nauvis, and Default, vanilla's, is one click away. Vanilla's
  `minecraft:normal` preset is left as it is, so a pack or a server can still make vanilla worlds;
  a server names Nauvis in `server.properties`. Its map seed is the world seed's low 32 bits,
  Factorio's being 32 bits: seed 123 is the map `./gradlew test` renders.
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
  a soil, dirt-like or sand-like, with its rooty look under the name Dynamic Trees gives it
  (`dynamictrees:rooty_nauvis_grass_1`, its namespace for a mod without its registry handler); a canceller for Nauvis's living trees; and
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
