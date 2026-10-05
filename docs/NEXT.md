Next session
============

Only what to pick up now and how to run things. Edit it down as jobs finish.

Where the mod stands
--------------------

A world type, `nauvis_terrain:nauvis`: Factorio's Nauvis ground, water and starting lake on terraces
4 blocks apart at Factorio's cliff levels, with cliff faces where Factorio draws cliffs and ramps in
its gaps; its tiles as blocks of their own with textures from Factorio's, over vanilla's
underground; its iron, copper, coal and stone patches as vanilla ore blocks one deep; and its trees,
rocks and decoratives as vanilla trees, plants and boulders. The world type's Customize button is
Factorio's map generator screen, presets included. `tools/gen_terrain.py` turns the dump into the
noise program (`core/src/main/resources/nauvis_terrain/noise/nauvis.json`, 1744 nodes, 113 roots),
core's `noise` package runs it with the engine's built-ins as `factorio.exe` computes them, and
places tiles, trees, rocks, ores and decoratives as Factorio's map generator does: around the start
every one is Factorio's (`FACTORIO.md`, what the oracle checks). Core's `Terraces` lays it out in
blocks; `mc-26.2` is the mod for Minecraft 26.2 and `mc-1.21.1` the same for 1.21.1
(`ARCHITECTURE.md`, versions). A new world starts on it. Project Nauvis runs it as its world, an
`includeBuild` at runtime in the pack mod: the pack places no ore of its own, its drill takes this
world's ores, and its oil fields and natural water reach the biome through
`#minecraft:is_overworld`. `./gradlew test` renders seed 123 into `core/build/nauvis-123-512.png`;
each version's `runGameTestServer` checks chunks of a real Nauvis world and measures it: 83 full
chunks a second over 400, where vanilla's Nether in the same run makes 37 (1.21.1: 83 and 34).
Both versions have been played in a client and look right.

The jobs
--------

### 1. In the pack

The world in Project Nauvis, which nobody has looked at yet: a drill on a surface patch one block
deep, and the oil fields and offshore pump on Nauvis ground and lakes (`:nauvis:runClient` in
`../ProjectNauvis`).

### 2. 26.3

Minecraft 26.3 is out, and NeoForge's 26.3 builds are betas (26.3.0.51-beta on 2026-10-05). Once
one is a release and Project Nauvis moves to it with its other mods, `mc-26.2` becomes `mc-26.3`:
the folder, its `gradle.properties`, `API-26.2.md` and the pack's substitution, every API it uses
checked against 26.3's sources, and the gametests run again.

How to run everything
---------------------

| | |
|---|---|
| `./gradlew build` | core's tests and every version's jar, in `mc-<version>/build/libs/` |
| `gh release create v<mod_version> mc-26.2/build/libs/*.jar mc-1.21.1/build/libs/*.jar` | a release on GitHub, each version's jar attached, after `mod_version` goes up in `gradle.properties` and `./gradlew clean build` |
| `./gradlew test` | core's tests: the evaluator against Factorio's values, and seed 123's render |
| `./gradlew :mc-26.2:runClient`, `:mc-1.21.1:runClient` | the game with the mod |
| `./gradlew :mc-26.2:runClientData` / `runServerData` | 26.2's models, textures and language / loot, tags, the biome and the world preset |
| `./gradlew :mc-1.21.1:runData` | 1.21.1's, in one run |
| `./gradlew :mc-26.2:runGameTestServer`, `:mc-1.21.1:runGameTestServer` | the gametests, in a Nauvis world |
| `python tools/gen_terrain.py --write` | the noise program, and a summary: nodes per root, the operations reached |
| `python -m unittest tools/test_gen_terrain.py` | the parser and the compiler |
| `python tools/compare.py [NAME ...] [--tiles X0,Y0,W,H] [--bare] [--autoplace TILE=EXPR]` | Factorio's values against ours, bit for bit, over 2 seeds unless `--seeds`; with `--tiles`, its generated tiles, entities and decoratives against `Terrain`'s, `--bare` without Factorio's enemies and cliffs, `--autoplace` under made-up tile probabilities |
| `python tools/compare.py --fixture` | `core/src/test/resources/factorio-values.json` and `factorio-chunks.json`, what `EvaluatorTest` holds the evaluator and `Terrain` to |
| `python tools/oracle.py preview SEED` | Factorio's own map preview of a seed |
| `python tools/make_textures.py [--preview]` | the tile and cliff textures, from Factorio's graphics |
| `python tools/factorio_docs.py` | Factorio's API pages as text, into `reference/factorio/docs/` |
