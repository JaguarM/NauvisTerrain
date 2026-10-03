Next session
============

Only what to pick up now and how to run things. Edit it down as jobs finish.

Where the mod stands
--------------------

A NeoForge mod that loads and does nothing. The design is in `ARCHITECTURE.md` and `NOISE.md`;
Factorio 2.0.77's dump is in `reference/factorio/`.

The jobs
--------

### 1. The generator

`tools/gen_terrain.py` and `data/terrain.json` (ARCHITECTURE, the pipeline): a tokenizer and
parser for the syntax in `NOISE.md`, names resolved through local scopes, noise functions
inlined, constants folded, identical nodes merged, and a refusal for any built-in it does not
know. The roots are everything Nauvis places by ARCHITECTURE's autoplace rule, less uranium, oil
and enemies. `--write` and `--check`, and a `checkTerrain` task in `./gradlew build` that skips
itself when the dump is absent (Project Nauvis's root `build.gradle` shows how). It prints the
node count per root and the built-ins reached.

### 2. The evaluator and the render

The `noise` package: load the program, run a batch, the built-ins in `NOISE.md`'s table with a
first `basis_noise`. A test renders seed 123 at 1024 by 1024 around 0,0 into `build/`: tiles in
their `map_color`, then cliffs, rocks, ores and trees over them as Factorio's preview draws them.
Trees have no `map_color`; Factorio charts them in `utility-constants`' `chart.default_color_by_type.tree`.

### 3. The oracle

Factorio's own picture of a seed:

```
"F:/Steam/steamapps/common/Factorio/bin/x64/factorio.exe" --generate-map-preview C:/Users/yanni/AppData/Local/Temp/nauvis-123.png --map-gen-seed 123 --map-preview-size 1024
```

One tile per pixel, north up, centred on 0,0 (`--map-preview-offset X,Y` moves it), in well under
a second. `--report-quantities iron-ore,copper-ore` adds amounts, `--map-gen-settings FILE` the
sliders. That judges the look.

For numbers, a small Factorio mod whose named expressions probe the built-ins, sampled with
`LuaSurface.calculate_tile_properties` and written with `helpers.write_file`, run without a
window; finding that route is part of the job. It answers: `basis_noise`'s value distribution and
feature size against `input_scale`, the same for each multioctave form, `expression_in_range` and
`random_penalty` on known inputs, where `starting_lake_positions` lands for a few seeds, and
whether a tile is sampled at its corner or its centre. Then calibrate job 2's noise until the
share of each tile matches Factorio's over many seeds.

### 4. The world type

A chunk generator, its biome source and a world preset, `nauvis_terrain:nauvis`. Read the 26.2
sources first: `ChunkGenerator`, `BiomeSource`, `WorldPreset`, with `NoiseBasedChunkGenerator`
and `FlatLevelSource` as models. The 21 tile blocks under Factorio's ids, a flat `map_color`
texture each for now; water and deepwater; the surface height and both water depths; the biome
(Factorio's water colour, spawns); spawn at 0,0. A gametest that a generated chunk's top blocks
are the evaluator's tiles.

### 5. Ores

`spot_noise` with the shared candidate series, iron, copper, coal and stone as vanilla blocks,
the starting-area patches. How deep a patch goes: one layer, or deeper with richness.

### 6. Cliffs

The terraces (ARCHITECTURE, the world): the step height, the ramp at a gap, water that spans two
levels, and the block a cliff face is made of. A Factorio cliff only goes to cliff explosives.

### 7. Trees, rocks and decoratives

Twenty trees, three rocks, 34 decoratives. A Factorio tree is one entity on one tile and gives
four wood: one block with a tall model, or a vanilla-shaped tree of logs and leaves. The rocks
are entities several tiles across (a big rock gives 20 stone, a huge rock stone and coal).
Decoratives are plants (no collision, replaceable, like short grass) and decals (mud, sand dunes)
that change the ground block. Every drop is a loot table of vanilla items. Fish: vanilla cod where
Factorio puts fish, or nothing.

### 8. Textures

Every block from Factorio's own graphics (`reference/README.md` says where), scaled down by a
script, the way Project Nauvis's `texture-workshop` makes its icons.

### 9. The map settings

Factorio's map generator screen in Minecraft's world creation: the seed, each control's
frequency, size and richness (water, trees, rocks, cliffs, each ore), the climate sliders, the
starting area, and Factorio's presets from `map-gen-presets` (rich resources, lakes, island and
the rest).

### 10. Speed

Chunks per second against a vanilla world, and a pregeneration of a few thousand chunks. Factorio
evaluates 32 by 32 tiles at a time; batching four Minecraft chunks, or caching per region, are the
levers.

### 11. Project Nauvis

Once a world is playable: the pack takes the mod as an `includeBuild`, decides whether its own
ore patches give way to these (its `OrePatches` approximates the autoplace this mod runs), and
points the preset's ores at its own blocks if it wants.

How to run everything
---------------------

| | |
|---|---|
| `./gradlew build` | the jar, in `build/libs/` |
| `./gradlew runClient` | the game with the mod |
