package com.jaguarm.nauvisterrain.world;

import com.jaguarm.nauvisterrain.noise.Evaluator;
import com.jaguarm.nauvisterrain.noise.Hash;
import com.jaguarm.nauvisterrain.noise.MapSettings;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;
import com.jaguarm.nauvisterrain.noise.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nauvis's ground for one world seed (docs/ARCHITECTURE.md, the world). Land rises in terraces, a
 * step at each of Factorio's cliff levels: a cliff face where Factorio draws a cliff, a ramp where
 * it leaves a gap. Each land column is its tile's block from the lowest terrace up, a liquid tile
 * is a pool cut in at the lowest terrace, and below that is stone, deepslate and bedrock. Where
 * Factorio puts a resource, its block replaces the top block.
 */
final class Ground {
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** Stone gives way to deepslate over these blocks above y 0, as vanilla's does. */
    private static final int DEEPSLATE_BLEND = 8;
    /** Bedrock thins out over these blocks above the bottom of the world. */
    private static final int BEDROCK_BLEND = 5;
    /** The lowest terrace: the cliff level below Factorio's first contour. Lower ground is on it. */
    private static final int LOWEST_LEVEL = -1;
    /** In a gap, the last part of a level's span, as a share of the interval, over which land ramps up to the next. */
    private static final double RAMP = 0.1;
    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int PLACED_KEPT = 4096;

    final Terrain terrain;
    final long mapSeed;
    /** The top of the lowest terrace, and the surface of every pool. */
    final int surface;
    private final int step;
    private final BlockState cliff;
    private final double cliffElevation0;
    private final double cliffInterval;
    private final BlockState[] block;
    private final BlockState[] floor;
    private final int[] depth;
    private final Map<String, BlockState> resources;
    /** Each chunk's trees, rocks and decoratives by Factorio name, on the ground they stand on. */
    private final Map<Long, Map<String, List<BlockPos>>> placed = new ConcurrentHashMap<>();

    Ground(NauvisSettings settings, long worldSeed) {
        this.mapSeed = worldSeed & 0xFFFFFFFFL;
        MapSettings map = settings.map().settings(mapSeed);
        this.terrain = new Terrain(new Evaluator(Nauvis.program(), map));
        this.surface = settings.surface();
        this.step = settings.cliff().step();
        this.cliff = settings.cliff().block();
        this.cliffElevation0 = map.cliffElevation0();
        this.cliffInterval = map.cliffElevationInterval();
        this.resources = settings.resources();
        List<Prototype> tiles = terrain.tiles;
        block = new BlockState[tiles.size()];
        floor = new BlockState[tiles.size()];
        depth = new int[tiles.size()];
        for (int t = 0; t < tiles.size(); t++) {
            String name = tiles.get(t).name();
            NauvisSettings.Tile tile = settings.tiles().get(name);
            if (tile == null) {
                throw new IllegalArgumentException("the world preset gives no block for the tile " + name);
            }
            block[t] = tile.block();
            floor[t] = tile.floor().orElse(STONE);
            depth[t] = tile.block().getFluidState().isEmpty() ? 1 : tile.depth();
        }
    }

    /** One column's shape: its tile, the y of its top block, and the lowest y of its cliff face, if it has one. */
    private record Column(int tile, int top, int faceFrom) {
    }

    /** The ground's blocks in a chunk, with its two worldgen heightmaps. */
    void fill(ChunkAccess chunk) {
        int x0 = chunk.getPos().getMinBlockX();
        int z0 = chunk.getPos().getMinBlockZ();
        Terrain.Area area = terrain.area(x0, z0, 16, 16);
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int minY = chunk.getMinY();
        int[][] tops = new int[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                Column column = column(area, x0 + x, z0 + z);
                tops[x][z] = column.top;
                for (int y = minY; y <= Math.min(column.top, chunk.getMaxY()); y++) {
                    BlockState state = state(column, x0 + x, y, z0 + z, minY);
                    if (state != AIR) {
                        chunk.getSection(chunk.getSectionIndex(y)).setBlockState(x, y & 15, z, state, false);
                    }
                }
                boolean liquid = liquid(column.tile);
                oceanFloor.update(x, liquid ? surface - depth[column.tile] : column.top, z,
                        liquid ? floor[column.tile] : block[column.tile]);
                worldSurface.update(x, column.top, z, block[column.tile]);
            }
        }
        for (Terrain.Placed placed : area.entities()) {
            BlockState ore = resources.get(placed.prototype().name());
            int top = tops[placed.x() - x0][placed.y() - z0];
            if (ore != null && placed.prototype().type().equals("resource") && top <= chunk.getMaxY()) {
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(top));
                section.setBlockState(placed.x() - x0, top & 15, placed.y() - z0, ore, false);
            }
        }
    }

    /** The y above the highest block in the column that the heightmap counts. */
    int height(int x, int z, Heightmap.Types type, int minY) {
        Column column = column(terrain.area(x, z, 1, 1), x, z);
        for (int y = column.top; y >= minY; y--) {
            if (type.isOpaque().test(state(column, x, y, z, minY))) {
                return y + 1;
            }
        }
        return minY;
    }

    /** The whole column from the bottom of the world up. */
    BlockState[] column(int x, int z, int minY, int height) {
        Column column = column(terrain.area(x, z, 1, 1), x, z);
        BlockState[] states = new BlockState[height];
        for (int i = 0; i < height; i++) {
            states[i] = state(column, x, minY + i, z, minY);
        }
        return states;
    }

    Prototype tile(int x, int z) {
        return terrain.tiles.get(terrain.area(x, z, 1, 1).tileIndex(x, z));
    }

    /** Where Factorio places a tree, rock or decorative in a chunk: on top of each tile it stands on. */
    List<BlockPos> placed(String name, ChunkPos chunk) {
        Map<String, List<BlockPos>> all = placed.get(chunk.pack());
        if (all == null) {
            all = placements(chunk);
            if (placed.size() >= PLACED_KEPT) {
                placed.clear();
            }
            placed.put(chunk.pack(), all);
        }
        return all.getOrDefault(name, List.of());
    }

    private Map<String, List<BlockPos>> placements(ChunkPos chunk) {
        Terrain.Area area = terrain.area(chunk.getMinBlockX(), chunk.getMinBlockZ(), 16, 16);
        Map<String, List<BlockPos>> out = new HashMap<>();
        List<Terrain.Placed> things = new ArrayList<>(area.entities());
        things.addAll(area.decoratives());
        for (Terrain.Placed p : things) {
            if (p.prototype().type().equals("resource")) {
                continue;
            }
            BlockPos pos = new BlockPos(p.x(), column(area, p.x(), p.y()).top + 1, p.y());
            List<BlockPos> list = out.computeIfAbsent(p.prototype().name(), k -> new ArrayList<>());
            if (!list.contains(pos)) {
                list.add(pos);
            }
        }
        return out;
    }

    /** The y of the top block at a column. */
    int top(int x, int z) {
        return column(terrain.area(x, z, 1, 1), x, z).top;
    }

    private boolean liquid(int tile) {
        return depth[tile] > 1;
    }

    private Column column(Terrain.Area area, int x, int z) {
        int tile = area.tileIndex(x, z);
        if (liquid(tile)) {
            return new Column(tile, surface, Integer.MAX_VALUE);
        }
        int top = landTop(area, x, z);
        int faceFrom = Integer.MAX_VALUE;
        if (area.cliffiness(x, z) > 0.5) {
            for (int[] d : NEIGHBOURS) {
                if (!liquid(area.tileIndex(x + d[0], z + d[1]))) {
                    int below = landTop(area, x + d[0], z + d[1]);
                    if (below < top) {
                        faceFrom = Math.min(faceFrom, below + 1);
                    }
                }
            }
        }
        return new Column(tile, top, faceFrom);
    }

    /** A land column's top: its terrace, and in a gap, its share of the ramp up to the next. */
    private int landTop(Terrain.Area area, int x, int z) {
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

    private BlockState state(Column column, int x, int y, int z, int minY) {
        if (y > column.top) {
            return AIR;
        }
        int tile = column.tile;
        if (liquid(tile)) {
            int bed = surface - depth[tile];
            if (y > bed) {
                return block[tile];
            }
            if (y == bed) {
                return floor[tile];
            }
        } else if (y >= surface) {
            return y >= column.faceFrom && y < column.top ? cliff : block[tile];
        }
        if (y < minY + BEDROCK_BLEND && (y == minY || chance(x, y, z, 1) < (double) (minY + BEDROCK_BLEND - y) / BEDROCK_BLEND)) {
            return BEDROCK;
        }
        if (y < 0 || y < DEEPSLATE_BLEND && chance(x, y, z, 2) < (double) (DEEPSLATE_BLEND - y) / DEEPSLATE_BLEND) {
            return DEEPSLATE;
        }
        return STONE;
    }

    private double chance(int x, int y, int z, int salt) {
        return Hash.unit(Hash.of(mapSeed + salt, x, y, z));
    }
}
