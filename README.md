Nauvis Terrain
==============

Factorio's Nauvis as a Minecraft world.

A world type that lays out the ground the way Factorio 2.0 does: grass, dirt, sand and red
desert, lakes, cliffs, forests, rocks and the little plants and decals between them, with
iron, copper, coal and stone in Factorio's patch shapes at the surface. The world is flat, one
Factorio tile to a block, and generated from Factorio's own noise expressions.

| | |
|---|---|
| Minecraft | 26.2 |
| NeoForge | 26.2.0.59 |

Playing
-------

A new world is a Nauvis world; Default, under World Type, is still vanilla's. Customize opens
Factorio's map generator screen, presets included, and a seed gives the map Factorio makes from
it. On a server, set `level-type=nauvis_terrain:nauvis` in `server.properties` before the world
is made.

Building
--------

```
./gradlew build
```

The jar lands in `mc-26.2/build/libs/`. Java 25 is required.
