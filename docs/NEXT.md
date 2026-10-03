Next session
============

Only what to pick up now and how to run things. Edit it down as jobs finish.

Where the mod stands
--------------------

A world type, `nauvis_terrain:nauvis`: Factorio's Nauvis ground, water and starting lake on
terraces 4 blocks apart at Factorio's cliff levels, with cliff faces where Factorio draws cliffs and
ramps in its gaps; its tiles as blocks of their own with textures from Factorio's, over vanilla's
underground; its iron, copper, coal and stone patches as vanilla ore blocks one deep; and its trees,
rocks and decoratives where Factorio puts them, as vanilla trees, plants and boulders. The world
type's Customize button is Factorio's map generator screen, presets included. `tools/gen_terrain.py` turns the dump into the
noise program (`src/main/resources/nauvis_terrain/noise/nauvis.json`, 1494 nodes, 113 roots), the
`noise` package runs it, calibrated against Factorio's own values until every tile's share, the
climate, the ores and the trees match (`FACTORIO.md`, what the oracle measured), and the `world`
package lays it out. `./gradlew test` renders seed 123 into `build/nauvis-123-512.png`;
`./gradlew runGameTestServer` checks chunks of a real Nauvis world and measures it: about 80 full
chunks a second asked for one at a time from the server thread, 99 over 2500, where vanilla's
Nether in the same run makes 36. A block of 32 by 32 tiles evaluates in about 7 ms.

The jobs
--------

### 1. Project Nauvis

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
| `./gradlew runClientData` / `runServerData` | models, textures and language / loot, tags, the biome and the world preset |
| `./gradlew runGameTestServer` | the gametests, in a Nauvis world |
| `python tools/gen_terrain.py --write` | the noise program, and a summary: nodes per root, the operations reached |
| `python -m unittest tools/test_gen_terrain.py` | the parser and the compiler |
| `python tools/calibrate.py [NAME ...]` | Factorio's values against ours, over 4 seeds unless `--seeds` |
| `python tools/oracle.py preview SEED` | Factorio's own map preview of a seed |
| `python tools/make_textures.py [--preview]` | the tile and cliff textures, from Factorio's graphics |
| `python tools/factorio_docs.py` | Factorio's API pages as text, into `reference/factorio/docs/` |
