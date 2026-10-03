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
writes without heightmaps or light.
