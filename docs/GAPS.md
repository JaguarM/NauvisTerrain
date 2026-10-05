What is deliberately missing
============================

Each a decision. If something looks broken, look here before treating it as a bug. Entries marked
*kept* are divergences from Factorio chosen on purpose and are not debt.

- Outside the area made with the map, chunks are corrected and made in an order of this world's
  own; Factorio's follows how the map is explored, and differs between explorations. At chunk edges
  a few trees, rocks and decoratives, and rarely a tile, are not what a given exploration of
  Factorio's would give.
- An ore near a start removes a tree on its tile by Factorio's chance, but the draw is a hash here:
  Factorio takes it from the game's own random generator, which no chunk can know.
- Land is one height between cliffs. Factorio's `elevation` decides only where water is. *Kept.*
- Cliffs follow the contours tile by tile, not on Factorio's 4 by 4 grid of cliff pieces, and a
  gap is a ramp. They are not entities, so they keep no tree, rock or decorative off, and no ore
  clears them. *Kept.*
- A lake on a plateau is a pool sunk into it: water lies only on the lowest terrace. Factorio's is
  flat ground beside flat water. *Kept.*
- Cliff levels below -1 are on the lowest terrace; under 1% of the map.
- No uranium and no crude oil: vanilla has no block for either.
- An ore's richness is not kept: a patch is one block deep and every block is one vanilla ore.
  A pack's Crumbling Ore makes a block last, its harvests growing in rings of distance from the
  start, which follow Factorio's richness by distance to within a tenth at Project Nauvis's 2600.
- No biter spawners and no worms, nor what comes with them: the red croton, red pita and mud
  decals around a base, and the trees and decoratives a base keeps off.
- Trees, plants and small rocks are vanilla's nearest, not Factorio's own: a leaf colour, a plant
  shape, a block of stone for a pebble. Decals are round. *Kept.*
- With Dynamic Trees, its trees replace Factorio's and are spaced by its rules, deserts bare only
  because its trees do not root in sand. *Kept.*
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

On 1.21.1
---------

What 1.21.1 lacks against 26.2. Its world is otherwise the same block for block.

- Plants and trees are of blocks 1.21.1 has: a fern for a bush, a dead bush for dry grass, dark
  oak's leaves on the brown trees for pale oak's, an acacia log for the grey trunk, and a single log
  on its side for a fallen tree. With Dynamic Trees, its dark oak for pale oak.
