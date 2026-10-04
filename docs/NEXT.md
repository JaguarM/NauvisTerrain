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
type's Customize button is Factorio's map generator screen, presets included.
`tools/gen_terrain.py` turns the dump into the noise program
(`src/main/resources/nauvis_terrain/noise/nauvis.json`, 1744 nodes, 113 roots), the `noise`
package runs it with the engine's built-ins as `factorio.exe` computes them, every root
Factorio's bit for bit (`FACTORIO.md`, what the oracle checks), and the `world` package lays it
out. Not yet seen in a client: the world creation screen, the map generator screen, and how it all
looks from a player's eyes and from Distant Horizons'. `./gradlew test` renders seed 123 into
`build/nauvis-123-512.png`; `./gradlew runGameTestServer` checks chunks of a real Nauvis world and
measures it: 97 full chunks a second over 400, where vanilla's Nether in the same run makes 33. A
block of 32 by 32 tiles evaluates in about 7 ms.

The jobs
--------

### 1. Factorio's tile correction

Generated tiles are the most probable one; Factorio then corrects about one in 500 at the borders
between tiles (`GAPS.md`). Its `TileCorrectionMapGenerationTask` is being read from the binary
into `reference/factorio/engine/tiles/`. Port it into `Terrain` after the tiles are chosen; then
`python tools/compare.py elevation --tiles -64,-64,128,128` agrees on every tile.

### 2. Factorio's entity and decorative placement

`EntityMapGenerationTask` replaces our pass over the probabilities: the batch each autoplace is
evaluated in, the rolls, the order things collide in, richness to amount. Its reading lands in
`reference/factorio/engine/entities/` and `decoratives/`.

### 3. Project Nauvis

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
| `python tools/compare.py [NAME ...] [--tiles X0,Y0,W,H] [--autoplace TILE=EXPR]` | Factorio's values against ours, bit for bit, over 2 seeds unless `--seeds`; with `--tiles`, its generated tiles against `Terrain`'s, under made-up tile probabilities with `--autoplace` |
| `python tools/compare.py --fixture` | `src/test/resources/factorio-values.json`, Factorio's values that `EvaluatorTest` holds the evaluator to |
| `python tools/oracle.py preview SEED` | Factorio's own map preview of a seed |
| `python tools/make_textures.py [--preview]` | the tile and cliff textures, from Factorio's graphics |
| `python tools/factorio_docs.py` | Factorio's API pages as text, into `reference/factorio/docs/` |
