Minecraft 26.3 API notes
========================

What `mc-26.3` is written against, set beside 26.2's (`API-26.2.md`), which holds everything 26.3
keeps. Check the sources before writing against any API; some mistakes fail silently.

Where the sources are
---------------------

- Minecraft: the `mergeWithSources_*_output.jar` for 26.3 under
  `~/.gradle/caches/neoformruntime/intermediate_results/`, as for 26.2.
- Vanilla's data: `mc-26.3/common/build/moddev/artifacts/vanilla-26.3-1-merged.jar`, `data/minecraft/`.
- Toolchain: Java 25, NeoForge 26.3.0.64-beta, NeoForm 26.3-1, ModDevGradle 2.0.148, Fabric Loader
  0.19.5, Fabric API 0.162.0+26.3.

Features and placements
-----------------------

- `Feature` is an interface with no configuration: a feature is a record implementing it, with
  `codec()` and `place(level, chunkGenerator, random, origin)`. Its `MapCodec` registers in
  `Registries.FEATURE_TYPE`; `Registries.FEATURE` holds the features themselves, what
  `CONFIGURED_FEATURE` was. `FeaturePlaceContext` and `feature.configurations` are gone;
  `RandomSelectorFeature` and `TreeFeature` are the records a test looks into.
- `PlacementModifier` is an interface: `modify(context, random, origin, Consumer<BlockPos>)` and
  `codec()`. The `MapCodec` registers in `Registries.PLACEMENT_MODIFIER_TYPE`;
  `PlacementModifierType` is gone.
- `BlockStateProvider.CODEC` is a `Holder<BlockStateProvider>`, read with `value()`.

A chunk generator
-----------------

- `fillFromNoise`, `buildSurface` and `applyCarvers` are one call, `buildTerrain(chunk, blender,
  randomState, structureManager, biomeManager, carverBiomeRegion, possibleBiomes)`, with
  `fillFromNoise`'s duties.
- `addDebugScreenInfo` takes a `SamplerContext` last.
- `NaturalSpawner.spawnMobsForChunkGeneration(region, sourcePos, chunkPos, random)` reads the
  spawns at a position, not of a biome.
- `NoiseGeneratorSettings.dummy()` is gone: a generator without noise settings gets
  `RandomState.create(noises, seed, false, Blocks.STONE.defaultBlockState(), 63,
  NoiseRouterData.none())`, as `ChunkMap` does.

Data
----

- Features are under `worldgen/feature/`, their fields beside `type` with no `config`.
- A block state is `{"id": ..., "properties": {...}}`, or its id alone where `BlockState.CODEC`
  reads it. A provider of one state is the state itself; the provider types are `minecraft:weighted`
  and `minecraft:rule_based`.
- A biome's mobs are an attribute, `minecraft:gameplay/natural_mob_spawns`, `{"argument":
  {"spawn_costs", "spawns_by_category"}, "modifier": "overlay"}`; a spawn's `count` is an int
  provider. `spawners` and `spawn_costs` are gone from the biome.
- Loot tables are a registry bootstrapped like any other, so `LootTableProvider` is no
  `DataProvider`; a self-drop is written as vanilla's `loot_table/blocks/dirt.json`.
- A datapack's format is 121.
- The gametest server's world is the `minecraft:flat_all_dimensions` world preset.
- `TestData` takes the dimension second: `environment, dimension, structure, ...`.
