Next session
============

Only what to pick up now and how to run things. Edit it down as jobs finish.

Where the mod stands
--------------------

A world type, `nauvis_terrain:nauvis`: Factorio's Nauvis ground, water and starting lake laid flat
at y 64, its tiles as blocks of their own with flat map-colour textures, over vanilla's underground,
and its iron, copper, coal and stone patches as vanilla ore blocks one deep. No cliffs, trees, rocks
or decoratives yet. `tools/gen_terrain.py` turns the dump into the
noise program (`src/main/resources/nauvis_terrain/noise/nauvis.json`, 1494 nodes, 113 roots), the
`noise` package runs it, calibrated against Factorio's own values until every tile's share, the
climate, the ores and the trees match (`FACTORIO.md`, what the oracle measured), and the `world`
package lays it out. `./gradlew test` renders seed 123 into `build/nauvis-123-512.png`;
`./gradlew runGameTestServer` checks chunks of a real Nauvis world. A block of 32 by 32 tiles takes
about 40 ms to evaluate.

The jobs
--------

### 1. Cliffs

The terraces (ARCHITECTURE, the world): the step height, the ramp at a gap, water that spans two
levels, and the block a cliff face is made of. Factorio's cliff cannot be mined and goes only to
cliff explosives, which this mod does not have; Project Nauvis has `nauvis:cliff_explosives`, so
what breaks a cliff wants to be data (a tag) the pack fills.

### 2. Trees, rocks and decoratives

Twenty trees, three rocks, 34 decoratives (`FACTORIO.md` has sizes, mining times and yields). A
Factorio tree is a small vanilla-shaped tree, oak-like or spruce-like, of logs and leaves, rooted
on the tile Factorio places it on (Yannic's call). Forests stay as dense as Factorio's: mods that
make such trees thinner and more walkable are the pack's to add. Decoratives are plants (no collision, replaceable, like short grass) and decals
(mud, sand dunes, up to 15 by 12 tiles) that change the ground block. Every drop is a loot table
of the items Project Nauvis already stands in for Factorio's, so the pack needs no override: wood
is `minecraft:oak_planks`, stone `minecraft:cobblestone`, coal `minecraft:coal`, raw fish
`minecraft:cod`. Fish: a cod where Factorio puts fish, or nothing.

### 3. Textures

Every block starts from Factorio's own graphics (`reference/README.md` says where). A tile's PNG is
an atlas of variants (`grass-1.png` is 4096 by 576); one variant scaled down is the starting
point. Project Nauvis's `texture-workshop/README.md` is the method its icons follow: an ASCII map
plus a palette per texture, vanilla's idiom, six colours enough for a rocky surface, previews not
committed. Yannic's rule for icons was "look at Factorio and then make it Minecraft pixels".

### 4. The map settings

Factorio's map generator screen in Minecraft's world creation: the seed, each control's
frequency, size and richness (water, trees, rocks, cliffs, each ore), the climate sliders
(`control:moisture:frequency` and `bias`, `control:aux:…`), the starting area, and the presets
(`FACTORIO.md`). `ribbon-world`'s map is 128 tiles tall, which needs a border or a skip.

### 5. Speed

Chunks per second against a vanilla world, and a pregeneration of a few thousand chunks. Factorio
evaluates 32 by 32 tiles at a time; batching four Minecraft chunks, or caching per region, are the
levers.

### 6. Project Nauvis

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
| `python tools/factorio_docs.py` | Factorio's API pages as text, into `reference/factorio/docs/` |
