package com.jaguarm.nauvisterrain.world;

import com.jaguarm.nauvisterrain.noise.Evaluator;
import com.jaguarm.nauvisterrain.noise.Hash;
import com.jaguarm.nauvisterrain.noise.MapSettings;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;
import com.jaguarm.nauvisterrain.noise.Terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * Nauvis's ground for one world seed, as the kind of block at each position (docs/ARCHITECTURE.md,
 * the world). Land rises in terraces, a step at each of Factorio's cliff levels: a cliff face where
 * Factorio draws a cliff, a ramp where it leaves a gap. Each land column is its tile from the
 * lowest terrace up, a liquid tile is a pool cut in at the lowest terrace, and below that is stone,
 * deepslate and bedrock. Near water both follow Factorio's elevation: a pool deepens as it falls
 * below zero, and land rises from the water as it climbs above. Each Minecraft version's chunk
 * generator gives each kind its block.
 */
public final class Terraces {
    /** The world preset's y of the lowest terrace's top block, and how many blocks one cliff level rises. */
    public static final int SURFACE = 64;
    public static final int STEP = 4;
    /** Stone gives way to deepslate over these blocks above y 0, as vanilla's does. */
    private static final int DEEPSLATE_BLEND = 8;
    /** Bedrock thins out over these blocks above the bottom of the world. */
    private static final int BEDROCK_BLEND = 5;
    /** The lowest terrace: the cliff level below Factorio's first contour. Lower ground is on it. */
    private static final int LOWEST_LEVEL = -1;
    /** In a gap, the last part of a level's span, as a share of the interval, over which land ramps up to the next. */
    private static final double RAMP = 0.1;
    /** Blocks a pool deepens for each unit of elevation below zero. */
    private static final double DEEPENING = 1;
    /** Blocks land rises from a pool's water for each unit of elevation above zero, up to its terrace. */
    private static final double BANK = 2;
    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int PLACED_KEPT = 4096;

    /** What a block of the ground is: its column's tile, a cliff's face, a pool's bed, or what lies under the land. */
    public enum Layer { AIR, TILE, CLIFF, FLOOR, STONE, DEEPSLATE, BEDROCK }

    /**
     * One column's shape: its tile, the y of its top block, the lowest y of its cliff face, if it has
     * one, and a pool's bed, the y of its floor.
     */
    public record Column(int tile, int top, int faceFrom, int bed) {
    }

    /** The block a tree, rock or decorative stands in: the one above its tile's top. */
    public record Spot(int x, int y, int z) {
    }

    /** A thing Factorio places, by its Factorio name, and the block it stands in. */
    public record Standing(String name, Spot spot) {
    }

    public final Terrain terrain;
    public final long mapSeed;
    /** The top of the lowest terrace, and the surface of every pool. */
    public final int surface;
    private final int step;
    private final double cliffElevation0;
    private final double cliffInterval;
    private final int[] depth;
    /** Each chunk's trees, rocks and decoratives by Factorio name, on the ground they stand on. */
    private final Map<Long, Map<String, List<Spot>>> placed = new ConcurrentHashMap<>();

    /**
     * The ground of a world seed under the map generator screen's settings: the lowest terrace's top
     * at `surface`, `step` blocks to a cliff level, and each tile's pool at most `depth` deep, by its
     * Factorio name, which is 0 for land.
     */
    public Terraces(NauvisMap map, long worldSeed, int surface, int step, ToIntFunction<String> depth) {
        this.mapSeed = worldSeed & 0xFFFFFFFFL;
        MapSettings settings = map.settings(mapSeed);
        this.terrain = new Terrain(new Evaluator(Nauvis.program(), settings));
        this.surface = surface;
        this.step = step;
        this.cliffElevation0 = settings.cliffElevation0();
        this.cliffInterval = settings.cliffElevationInterval();
        this.depth = terrain.tiles.stream().mapToInt(t -> depth.applyAsInt(t.name())).toArray();
    }

    public boolean liquid(int tile) {
        return depth[tile] > 0;
    }

    public Prototype tile(int x, int z) {
        return terrain.tiles.get(terrain.area(x, z, 1, 1).tileIndex(x, z));
    }

    /** The y of the top block at a column. */
    public int top(int x, int z) {
        return column(x, z).top;
    }

    public Column column(int x, int z) {
        return column(terrain.area(x, z, 1, 1), x, z);
    }

    public Column column(Terrain.Area area, int x, int z) {
        int tile = area.tileIndex(x, z);
        if (liquid(tile)) {
            int deep = (int) Math.floor(1 - area.elevation(x, z) * DEEPENING);
            return new Column(tile, surface, Integer.MAX_VALUE, surface - Math.min(Math.max(deep, 1), depth[tile]));
        }
        int terrace = terraceTop(area, x, z);
        int faceFrom = Integer.MAX_VALUE;
        if (area.cliffiness(x, z) > 0.5) {
            for (int[] d : NEIGHBOURS) {
                int nx = x + d[0];
                int nz = z + d[1];
                if (!liquid(area.tileIndex(nx, nz)) && terraceTop(area, nx, nz) < terrace) {
                    faceFrom = Math.min(faceFrom, landTop(area, nx, nz) + 1);
                }
            }
        }
        return new Column(tile, landTop(area, x, z), faceFrom, Integer.MIN_VALUE);
    }

    /** What the block at y is in a column, in a world whose lowest y is `minY`. */
    public Layer layer(Column column, int x, int y, int z, int minY) {
        if (y > column.top) {
            return Layer.AIR;
        }
        if (liquid(column.tile)) {
            if (y > column.bed) {
                return Layer.TILE;
            }
            if (y == column.bed) {
                return Layer.FLOOR;
            }
        } else if (y >= surface) {
            return y >= column.faceFrom && y < column.top ? Layer.CLIFF : Layer.TILE;
        }
        if (y < minY + BEDROCK_BLEND && (y == minY || chance(x, y, z, 1) < (double) (minY + BEDROCK_BLEND - y) / BEDROCK_BLEND)) {
            return Layer.BEDROCK;
        }
        if (y < 0 || y < DEEPSLATE_BLEND && chance(x, y, z, 2) < (double) (DEEPSLATE_BLEND - y) / DEEPSLATE_BLEND) {
            return Layer.DEEPSLATE;
        }
        return Layer.STONE;
    }

    /**
     * Where Factorio places a tree, rock or decorative in the Minecraft chunk at `chunkX`, `chunkZ`,
     * 16 by 16 blocks: in the block above each tile it stands on.
     */
    public List<Spot> placed(String name, int chunkX, int chunkZ) {
        long key = chunkX & 0xFFFFFFFFL | (chunkZ & 0xFFFFFFFFL) << 32;
        Map<String, List<Spot>> all = placed.get(key);
        if (all == null) {
            all = placements(chunkX * 16, chunkZ * 16);
            if (placed.size() >= PLACED_KEPT) {
                placed.clear();
            }
            placed.put(key, all);
        }
        return all.getOrDefault(name, List.of());
    }

    /**
     * The trees, rocks or decoratives of the given names in the Minecraft chunk at `chunkX`, `chunkZ`,
     * thinned so that no two kept stand closer than `spacing` blocks: a spot stays where it outranks
     * every other that close, by a hash of its place, which every chunk around it sees alike.
     */
    public List<Standing> thinned(Set<String> names, int chunkX, int chunkZ, int spacing) {
        Map<Spot, String> near = new LinkedHashMap<>();
        for (int cx = chunkX - 1; cx <= chunkX + 1; cx++) {
            for (int cz = chunkZ - 1; cz <= chunkZ + 1; cz++) {
                for (String name : names) {
                    for (Spot spot : placed(name, cx, cz)) {
                        near.putIfAbsent(spot, name);
                    }
                }
            }
        }
        List<Standing> kept = new ArrayList<>();
        near.forEach((spot, name) -> {
            if (Math.floorDiv(spot.x, 16) == chunkX && Math.floorDiv(spot.z, 16) == chunkZ && outranks(spot, near.keySet(), spacing)) {
                kept.add(new Standing(name, spot));
            }
        });
        return kept;
    }

    private boolean outranks(Spot spot, Set<Spot> near, int spacing) {
        for (Spot other : near) {
            int dx = other.x - spot.x;
            int dz = other.z - spot.z;
            if (dx * dx + dz * dz < spacing * spacing && rank(other) > rank(spot)) {
                return false;
            }
        }
        return true;
    }

    /** A spot's rank: a hash of its place. */
    private long rank(Spot spot) {
        return Hash.of(mapSeed + 3, spot.x, spot.z, 0);
    }

    private Map<String, List<Spot>> placements(int x0, int z0) {
        Terrain.Area area = terrain.area(x0, z0, 16, 16);
        Map<String, List<Spot>> out = new HashMap<>();
        List<Terrain.Placed> things = new ArrayList<>(area.entities());
        things.addAll(area.decoratives());
        for (Terrain.Placed p : things) {
            if (p.prototype().type().equals("resource")) {
                continue;
            }
            Spot spot = new Spot(p.x(), column(area, p.x(), p.y()).top + 1, p.y());
            List<Spot> list = out.computeIfAbsent(p.prototype().name(), k -> new ArrayList<>());
            if (!list.contains(spot)) {
                list.add(spot);
            }
        }
        return out;
    }

    /** A land column's top: its terrace, but no higher than its bank rises from the water. */
    private int landTop(Terrain.Area area, int x, int z) {
        int bank = surface + (int) Math.floor(Math.max(area.elevation(x, z), 0) * BANK);
        return Math.min(terraceTop(area, x, z), bank);
    }

    /** A land column's terrace, and in a gap, its share of the ramp up to the next. */
    private int terraceTop(Terrain.Area area, int x, int z) {
        double u = (area.cliffElevation(x, z) - cliffElevation0) / cliffInterval;
        int level = (int) Math.floor(u);
        if (level < LOWEST_LEVEL) {
            return surface;
        }
        int top = surface + step * (level - LOWEST_LEVEL);
        if (area.cliffiness(x, z) <= 0.5) {
            double into = (u - level - (1 - RAMP)) / RAMP;
            top += (int) Math.floor(step * Math.min(Math.max(into, 0), 1));
        }
        return top;
    }

    private double chance(int x, int y, int z, int salt) {
        return Hash.unit(Hash.of(mapSeed + salt, x, y, z));
    }
}
