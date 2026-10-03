Next session
============

Only what to pick up now and how to run things. Edit it down as jobs finish.

Where the mod stands
--------------------

A NeoForge mod that loads and does nothing, and the noise program it will run:
`tools/gen_terrain.py` turns the dump into `src/main/resources/nauvis_terrain/noise/nauvis.json`,
1426 nodes and 113 roots, and `checkTerrain` in `./gradlew build` tests the generator and diffs the
program. The design is in `ARCHITECTURE.md` and `NOISE.md`, what Factorio holds is in
`FACTORIO.md`, and Factorio 2.0.77's dump, its API pages as text and a preview of seed 123 are in
`reference/factorio/`.

The jobs
--------

### 1. The evaluator and the render

The `noise` package: load the program, run a batch, the built-ins in `NOISE.md`'s table with a
first `basis_noise`. It knows no Minecraft (CLAUDE.md, rule 6), so it is tested with plain JUnit;
gametests are for what needs a world. A test renders seed 123 at 512 by 512 around 0,0 into
`build/`: tiles in their `map_color`, then cliffs, rocks, ores and trees over them as Factorio's
preview draws them (`FACTORIO.md` has the colours). Set it beside
`reference/factorio/previews/nauvis-123-512.png`.

### 2. The oracle

Factorio's own picture of any seed and settings, from the command line (`FACTORIO.md`, the map
preview). That judges the look.

For numbers, a small Factorio mod whose named expressions probe the built-ins, sampled with
`LuaSurface.calculate_tile_properties` and written with `helpers.write_file`, run without a
window; finding that route is part of the job. It answers: `basis_noise`'s value distribution and
feature size against `input_scale`, the same for each multioctave form, `expression_in_range` and
`random_penalty` on known inputs, where `starting_lake_positions` lands for a few seeds, and
whether a tile is sampled at its corner or its centre. Then calibrate job 1's noise until the
share of each tile matches Factorio's over many seeds.

### 3. The world type

A chunk generator, its biome source and a world preset, `nauvis_terrain:nauvis`. Read the 26.2
sources first: `ChunkGenerator`, `BiomeSource`, `WorldPreset`, with `NoiseBasedChunkGenerator`
and `FlatLevelSource` as models, and how a generator runs biome decoration (ARCHITECTURE, the
world). The biome is data (`worldgen/biome/nauvis.json`, Factorio's water colour) and joins
`#minecraft:is_overworld`. The 21 tile blocks under Factorio's ids, a flat `map_color` texture
each for now, named from `data/base/locale/en/base.cfg` (`[tile-name]`: "Grass", "Grass 2", "Dirt
1"); water and deepwater; the surface height and both water depths; spawn at 0,0. Datagen runs
are `clientData` and `serverData` (`API-26.2.md`). A gametest, with Project Nauvis's `GameTests`
copied in, that a generated chunk's top blocks are the evaluator's tiles.

### 4. Ores

`spot_noise` with the shared candidate series, iron, copper, coal and stone as `iron_ore`,
`copper_ore`, `coal_ore` and `stone`, and the starting-area patches. Factorio's shape is Yannic's
call; Project Nauvis found thirty-to-forty-block patches too big underground and shrank its own,
which is a reason to look at a patch in game early, not to change it. How deep a patch goes: one
layer, or deeper with richness.

### 5. Cliffs

The terraces (ARCHITECTURE, the world): the step height, the ramp at a gap, water that spans two
levels, and the block a cliff face is made of. Factorio's cliff cannot be mined and goes only to
cliff explosives, which this mod does not have; Project Nauvis has `nauvis:cliff_explosives`, so
what breaks a cliff wants to be data (a tag) the pack fills.

### 6. Trees, rocks and decoratives

Twenty trees, three rocks, 34 decoratives (`FACTORIO.md` has sizes, mining times and yields). A
Factorio tree is one entity on one tile: one block with a tall model, or a vanilla-shaped tree of
logs and leaves. Decoratives are plants (no collision, replaceable, like short grass) and decals
(mud, sand dunes, up to 15 by 12 tiles) that change the ground block. Every drop is a loot table
of the items Project Nauvis already stands in for Factorio's, so the pack needs no override: wood
is `minecraft:oak_planks`, stone `minecraft:cobblestone`, coal `minecraft:coal`, raw fish
`minecraft:cod`. Fish: a cod where Factorio puts fish, or nothing.

### 7. Textures

Every block starts from Factorio's own graphics (`reference/README.md` says where). A tile's PNG is
an atlas of variants (`grass-1.png` is 4096 by 576); one variant scaled down is the starting
point. Project Nauvis's `texture-workshop/README.md` is the method its icons follow: an ASCII map
plus a palette per texture, vanilla's idiom, six colours enough for a rocky surface, previews not
committed. Yannic's rule for icons was "look at Factorio and then make it Minecraft pixels".

### 8. The map settings

Factorio's map generator screen in Minecraft's world creation: the seed, each control's
frequency, size and richness (water, trees, rocks, cliffs, each ore), the climate sliders
(`control:moisture:frequency` and `bias`, `control:aux:…`), the starting area, and the presets
(`FACTORIO.md`). `ribbon-world`'s map is 128 tiles tall, which needs a border or a skip.

### 9. Speed

Chunks per second against a vanilla world, and a pregeneration of a few thousand chunks. Factorio
evaluates 32 by 32 tiles at a time; batching four Minecraft chunks, or caching per region, are the
levers.

### 10. Project Nauvis

Once a world is playable, the pack takes the mod as an `includeBuild`. What already meets it there:

- `nauvis:ore_patch` adds the pack's own underground patches to `#minecraft:is_overworld`
  (`nauvis/.../ore/OrePatches.java`); with this world it either goes or moves to the surface.
- The pack's drill takes `#nauvis_mining:factorio_ores`, which is `#c:ores/iron`, `copper` and
  `coal`, so vanilla's ore blocks from this world already count.
- `nauvis_fluids:natural_water` swaps vanilla water for the pack's in the last decoration step,
  and `nauvis_fluids:crude_oil_field` places oil; both reach this world through the biome tag.
  The oil field's placement approximates Factorio's autoplace and could read this program's
  `crude-oil` instead.
- The pack's pollution absorption reads the biome; Factorio's is per tile (`FACTORIO.md`), which
  this world's ground block would give it.

How to run everything
---------------------

| | |
|---|---|
| `./gradlew build` | the jar, in `build/libs/` |
| `./gradlew runClient` | the game with the mod |
| `./gradlew runClientData` / `runServerData` | models and language / loot and tags |
| `python tools/gen_terrain.py --write` | the noise program, and a summary: nodes per root, the operations reached |
| `python -m unittest tools/test_gen_terrain.py` | the parser and the compiler |
| `python tools/factorio_docs.py` | Factorio's API pages as text, into `reference/factorio/docs/` |
