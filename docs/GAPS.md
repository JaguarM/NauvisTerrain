What is deliberately missing
============================

Each a decision. If something looks broken, look here before treating it as a bug. Entries marked
*kept* are divergences from Factorio chosen on purpose and are not debt.

- A seed does not make Factorio's map for that seed. `basis_noise`, `spot_noise` and the random
  penalties are ours (CLAUDE.md, rule 1); the rules on top of them are Factorio's.
- Things are placed by rank, not one after another: a candidate gives way to every earlier one
  in its way, even one that itself gave way. Factorio's sequential pass would let it stand. A few
  rocks and decoratives fewer, and every chunk decided alone.
- `^` and `pow` are exact. Factorio's are an approximation, so a tile sitting on a threshold can
  come out differently.
- Land is one height between cliffs. Factorio's `elevation` decides only where water is. *Kept.*
- Cliffs follow the contours tile by tile, not on Factorio's 4 by 4 grid of cliff pieces, and a
  gap is a ramp. *Kept.*
- A lake on a plateau is a pool sunk into it: water lies only on the lowest terrace. Factorio's is
  flat ground beside flat water. *Kept.*
- Cliff levels below -1 are on the lowest terrace; under 1% of the map.
- No uranium and no crude oil: vanilla has no block for either.
- An ore's richness is not kept: a patch is one block deep and every block is one vanilla ore.
  A pack's CrumblingOre makes an ore last, and its distance rule is Factorio's richness rule.
- No biter spawners and no worms.
- Trees, plants and small rocks are vanilla's nearest, not Factorio's own: a leaf colour, a plant
  shape, a stone button for a pebble. Decals are round. *Kept.*
- Fish are cod that spawn in water, not one fish on a hundredth of the water tiles.
- A tree, rock or plant yields what its vanilla blocks yield, not Factorio's wood and stone counts.
- Under the ground is Minecraft's: stone, deepslate, bedrock and vanilla's ores other than iron,
  copper and coal. Factorio has no underground. No caves. *Kept.*
- One starting position, at 0,0.
- Presets: no ribbon world, whose map is 128 tiles tall; no marathon or death worlds, whose map is
  the default's. No starting area slider, which moves only enemies, and none for temperature, as
  Factorio has none on Nauvis.
- Animals and monsters: Nauvis has neither, the biome has plains'. The mod alone is survivable,
  and a pack changes spawns with biome modifiers. *Kept.*
