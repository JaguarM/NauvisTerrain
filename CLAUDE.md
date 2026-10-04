Nauvis Terrain
==============

Factorio 2.0's Nauvis as a Minecraft 26.2 / NeoForge world type: its ground, water, cliffs, trees,
rocks, decoratives and ore patches, laid out by Factorio's own noise expressions on a flat world,
one tile to a block. `docs/NEXT.md` says what to pick up and how to run everything. Read
`docs/PITFALLS.md` before writing code and `docs/API-26.2.md` before writing against any
Minecraft API. `docs/ARCHITECTURE.md` is the rules the world is built to, `docs/FACTORIO.md` what
Factorio's install and dump hold about Nauvis, `docs/NOISE.md` Factorio's noise language and how
its engine computes each built-in, `docs/GAPS.md` what is deliberately missing.

Non-negotiables
---------------

1. **Factorio's rules, Factorio's noise.** Every expression, constant, autoplace rule and map
   setting is Factorio's, read from its own `data.raw`, and the engine's built-ins
   (`basis_noise`, `spot_noise` and the rest) are computed as `factorio.exe` computes them, read
   from the binary through its PDB (`reference/factorio/engine/`) and held to Factorio's own
   values bit for bit (`tools/compare.py`). A seed makes the map Factorio makes from it, except
   where `docs/GAPS.md` says otherwise.
2. **Generated, never typed.** `tools/gen_terrain.py` turns `reference/factorio/data-raw-<version>.json`
   plus `data/terrain.json` into the noise program the mod runs. It has `--check`, which diffs
   against disk, and `./gradlew build` runs it.
3. **Ids are Factorio's.** `grass-1` is `nauvis_terrain:grass_1`, `tree-08-brown` is
   `nauvis_terrain:tree_08_brown`. Ids live in world saves.
4. **Standalone.** The mod depends on no other mod. Ores are vanilla blocks; uranium, oil and
   enemies are not placed. A pack changes what it wants through data: the world preset, biome
   modifiers, loot tables, tags.
5. **Verify every 26.x API against the decompiled sources.** Minecraft 26.2 postdates training
   and guessed names fail silently. `docs/API-26.2.md` says where the sources are.
6. **The noise code knows nothing of Minecraft.** The `noise` package imports no `net.minecraft`
   class, so the evaluator runs, renders and is tested without booting the game.

Writing
-------

A javadoc is its first paragraph: what the thing is. Why it is that way is written once, in
`docs/ARCHITECTURE.md` for a rule and `docs/PITFALLS.md` for a way of getting it wrong, and is
not repeated in code. No history anywhere: a doc says what is true now, and git says how it got
there. A gametest is a lambda in `GameTests.add`, and a class only when it needs fields.

Factorio's files
----------------

Factorio 2.0.77 is installed at `F:\Steam\steamapps\common\Factorio`, and everything in it may be
read and derived from: the dump, the Lua under `data\`, the API docs under `doc-html\`, the
graphics. Textures start from Factorio's own graphics. `reference/` is gitignored in full;
`reference/README.md` says what goes there.

The sibling
-----------

`../ProjectNauvis` is the modpack this was made for. It will take this mod as an `includeBuild`
and change what it needs through data, never by compiling against it.
