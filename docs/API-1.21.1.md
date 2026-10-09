Minecraft 1.21.1 API notes
==========================

What `mc-1.21.1` is written against, set beside 26.2's names (`API-26.2.md`). Check the sources
before writing; a name from the other version fails to compile at best and passes silently at
worst.

Where the sources are
---------------------

- Minecraft and NeoForge: `mc-1.21.1/neoforge/build/moddev/artifacts/neoforge-21.1.255-sources.jar`,
  made by `./gradlew :mc-1.21.1:neoforge:createMinecraftArtifacts`. `unzip -p <jar> net/minecraft/.../X.java`.
- Vanilla's data and assets: `neoforge-21.1.255-client-extra-aka-minecraft-resources.jar` beside it,
  `data/minecraft/worldgen/` among them: the shape every worldgen JSON here must have.
- Toolchain: Java 21, NeoForge 21.1.255, ModDevGradle 2.0.143, FML 4.0.45.

26.2's names in 1.21.1
----------------------

| 26.2 | 1.21.1 |
|---|---|
| `Identifier` | `ResourceLocation` (`fromNamespaceAndPath`, `withDefaultNamespace`, `parse`) |
| `ResourceKey#identifier()` | `location()` |
| `getMinY()`, `getMaxY()` | `getMinBuildHeight()`, `getMaxBuildHeight() - 1`: the maximum is exclusive |
| `ChunkPos.containing(pos)`, `x()`, `z()` | `new ChunkPos(pos)`, fields `x`, `z`, `toLong()` |
| `net.minecraft.util.Util` | `net.minecraft.Util` |
| `Util.backgroundExecutor().forName(name)` | `Util.wrapThreadWithTaskName(name, task)` on `Util.backgroundExecutor()` |
| `applyCarvers(..., chunk)` | `applyCarvers(..., chunk, GenerationStep.Carving)`, once per step |
| `BlockStateProvider#getState(level, random, pos)` | `getState(random, pos)` |
| `registryAccess().lookupOrThrow(key).getValue(id)` | `registryAccess().registryOrThrow(key).get(id)` |
| `RandomFeatureConfiguration#features()`, `WeightedPlacedFeature#feature()` | fields `features`, `feature` |
| `PlacedFeature#getFeatures()`, holders | a stream of `ConfiguredFeature`s |
| `new ProtoChunk(pos, upgrade, level, level.palettedContainerFactory(), null)` | `new ProtoChunk(pos, upgrade, level, biomeRegistry, null)` |
| `GameTestHelper#assertionException(message)` | `new GameTestAssertException(message)` |
| `registerSimpleBlock(name, properties -> ...)` | `registerSimpleBlock(name, Properties.of()...)`; `registerBlock(name, factory, properties)` |
| `OptionInstance.SliderableEnum` | none; an `OptionInstance.IntRange` over the values' indices |

Data
----

- Datagen is one run, `runData`, into `src/generated/resources`: a `GatherDataEvent` whose
  `includeClient()` and `includeServer()` say which providers run. Models are NeoForge's
  `BlockStateProvider`, whose `ExistingFileHelper` checks every texture against `--existing`, and an
  item's model is `models/item/<name>.json`; there is no `items/` folder.
- `neoforge.mods.toml` starts with `modLoader="javafml"` and `loaderVersion="[4,)"`; without them
  FML refuses the mod ("Missing ModLoader").
- A datapack's `pack.mcmeta` is `{"pack": {"description": ..., "pack_format": 48}}`.
- A biome has no `attributes`: its `effects` need `fog_color`, `sky_color`, `water_color` and
  `water_fog_color` as numbers, and its `carvers` is a map by carving step, `{}` for none.
- A tree has `dirt_provider` and `force_dirt`, not `below_trunk_provider`, and turns the ground under
  its trunk to dirt unless the ground is `#minecraft:dirt` or its block's `onTreeGrow` (NeoForge)
  returns true. A disk's `state_provider` has no `type`.
- Not in 1.21.1: `bush`, `short_dry_grass`, `tall_dry_grass`, `leaf_litter`, `firefly_bush`, the
  pale oak blocks and the `minecraft:fallen_tree` feature.

Events
------

`@EventBusSubscriber(modid = ...)` takes game and mod bus listeners in one class, as in 26.2; its
`bus` is deprecated. `@SubscribeEvent` methods may be package-private.

Gametests
---------

A test is a `TestFunction`, a lambda with a batch, a name and a structure, from a static
`@GameTestGenerator` method of a class handed to `RegisterGameTestsEvent#register`. NeoForge keeps
a generated test only when its structure's namespace is in `neoforge.enabledGameTestNamespaces`. A
gametest server in development reads a structure first from `gameteststructures/<path>.snbt` in
its directory, by the path alone; `{DataVersion: 3955, size: [1, 1, 1], data: [], entities: [],
palette: []}` is one block of nothing. The server makes its world from the `minecraft:flat` world
preset with every datapack in `world/datapacks`, keeps that world between runs, and agrees to the
EULA by itself.
