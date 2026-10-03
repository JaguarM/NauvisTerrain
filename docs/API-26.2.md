Minecraft 26.2 API notes
========================

26.x postdates training. Check the sources before writing against any API; some mistakes fail
silently.

Where the sources are
---------------------

- Minecraft: `~/.gradle/caches/neoformruntime/intermediate_results/mergeWithSources_*_output.jar`,
  one per version; `mergeWithSources_0fead73...` is 26.2. `unzip -p <jar> net/minecraft/.../X.java`.
- NeoForge: `~/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/<version>/*/neoforge-<version>-sources.jar`.
- Toolchain: Java 25, NeoForge 26.2.0.59, ModDevGradle 2.0.143.
- Project Nauvis's `docs/API-26.2.md` has the renames met so far across a whole modpack.

Renames
-------

| Was | Is |
|---|---|
| `ResourceLocation` | `Identifier` (`fromNamespaceAndPath`, `withDefaultNamespace`, `parse`) |
| `ResourceKey#location()` | `identifier()` |
| `getMinBuildHeight()` | `LevelHeightAccessor.getMinY()` |
| `new ChunkPos(BlockPos)` | `ChunkPos.containing(pos)`; a record with `x()`, `z()`, `pack()`, `unpack(long)` |

Worldgen
--------

`worldgen/configured_feature/<name>.json`, `worldgen/placed_feature/<name>.json` and
`neoforge/biome_modifier/<name>.json` (`neoforge:add_features` or `neoforge:remove_features`, a
`step` from `GenerationStep.Decoration`). A biome modifier names its biomes by tag, usually
`#minecraft:is_overworld`. `WorldGenLevel.getHeight(Heightmap.Types, x, z)` primes a missing
heightmap; `MOTION_BLOCKING` stops at leaves and water. `section.setBlockState(x, y, z, state)`
writes without heightmaps or light. `Mth.getSeed(Vec3i)` is the per-position hash for a
deterministic `RandomSource`.

Gametests
---------

A test is a `GameTestInstance` in `Registries.TEST_INSTANCE` (`RegisterGameTestsEvent`) plus a
`MapCodec` type in `Registries.TEST_INSTANCE_TYPE`; a missing type passes every test and breaks a
client. `@GameTest` on a static method and `FunctionGameTestInstance` are unavailable to mods.
`structure` is mandatory (`minecraft:empty` is a point; a missing one silently does not run).
Structure `DataVersion` is 4903. `TestData` carries `environment, structure, maxTicks,
setupTicks, required, rotation, manualOnly, maxAttempts, requiredSuccesses, skyAccess, padding`.
A test helper named `run` on the enclosing class is shadowed by `GameTestInstance.run`.
Project Nauvis's `nauvis_lib/src/main/java/com/jaguarm/nauvislib/test/GameTests.java` and
`PackGameTest.java` do both registrations behind `tests.add(name, maxTicks, helper -> ...)`; they
are ours and MIT, so copy them rather than depend on them.

Datagen and data layout
-----------------------

Client and server datagen are separate runs (`clientData()`, `serverData()`); each deletes output
it does not recognise, so they write to `src/generated/client` and `src/generated/server`. A
generated file with a hand-written twin fails `processResources` as a duplicate; delete the
hand-written one. `data/<ns>/loot_table/` is singular. A flat item icon needs both
`assets/<ns>/items/<name>.json` and `models/item/<name>.json`. `Block.getLootTable()` is
`Optional`; `noLootTable()` makes it empty and datagen skips it.
