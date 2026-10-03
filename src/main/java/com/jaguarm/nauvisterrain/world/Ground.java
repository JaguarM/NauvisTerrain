package com.jaguarm.nauvisterrain.world;

import com.jaguarm.nauvisterrain.noise.Evaluator;
import com.jaguarm.nauvisterrain.noise.Hash;
import com.jaguarm.nauvisterrain.noise.MapSettings;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;
import com.jaguarm.nauvisterrain.noise.Terrain;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.Map;

/**
 * Nauvis's ground for one world seed: each column the tile's block on top of the land, or its
 * liquid cut into it, over stone, deepslate and bedrock, and a resource's block in place of the
 * top block where Factorio puts one (docs/ARCHITECTURE.md, the world).
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

    final Terrain terrain;
    final long mapSeed;
    final int surface;
    private final BlockState[] block;
    private final BlockState[] floor;
    private final int[] depth;
    private final Map<String, BlockState> resources;

    Ground(NauvisSettings settings, long worldSeed) {
        this.mapSeed = worldSeed & 0xFFFFFFFFL;
        this.terrain = new Terrain(new Evaluator(Nauvis.program(), MapSettings.defaults(mapSeed)));
        this.surface = settings.surface();
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

    /** The ground's blocks in a chunk, with its two worldgen heightmaps. */
    void fill(ChunkAccess chunk) {
        int x0 = chunk.getPos().getMinBlockX();
        int z0 = chunk.getPos().getMinBlockZ();
        Terrain.Area area = terrain.area(x0, z0, 16, 16);
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int minY = chunk.getMinY();
        int top = Math.min(surface, chunk.getMaxY());
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int tile = tileIndex(area, x0 + x, z0 + z);
                for (int y = minY; y <= top; y++) {
                    BlockState state = state(tile, x0 + x, y, z0 + z, minY);
                    if (state == AIR) {
                        continue;
                    }
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                    section.setBlockState(x, y & 15, z, state, false);
                }
                int floorY = surface - depth[tile];
                oceanFloor.update(x, depth[tile] > 1 ? floorY : surface, z, depth[tile] > 1 ? floor[tile] : block[tile]);
                worldSurface.update(x, surface, z, block[tile]);
            }
        }
        if (surface <= chunk.getMaxY()) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(surface));
            for (Terrain.Placed placed : area.entities()) {
                BlockState ore = resources.get(placed.prototype().name());
                if (ore != null && placed.prototype().type().equals("resource")) {
                    section.setBlockState(placed.x() - x0, surface & 15, placed.y() - z0, ore, false);
                }
            }
        }
    }

    /** The y above the highest block in the column that the heightmap counts. */
    int height(int x, int z, Heightmap.Types type, int minY) {
        int tile = tileIndex(terrain.area(x, z, 1, 1), x, z);
        for (int y = surface; y >= minY; y--) {
            if (type.isOpaque().test(state(tile, x, y, z, minY))) {
                return y + 1;
            }
        }
        return minY;
    }

    /** The whole column from the bottom of the world up. */
    BlockState[] column(int x, int z, int minY, int height) {
        int tile = tileIndex(terrain.area(x, z, 1, 1), x, z);
        BlockState[] states = new BlockState[height];
        for (int i = 0; i < height; i++) {
            int y = minY + i;
            states[i] = y > surface ? AIR : state(tile, x, y, z, minY);
        }
        return states;
    }

    Prototype tile(int x, int z) {
        return terrain.tiles.get(tileIndex(terrain.area(x, z, 1, 1), x, z));
    }

    private int tileIndex(Terrain.Area area, int x, int z) {
        return area.tileIndex(x, z);
    }

    private BlockState state(int tile, int x, int y, int z, int minY) {
        if (y > surface) {
            return AIR;
        }
        int floorY = surface - depth[tile];
        if (y > floorY) {
            return block[tile];
        }
        if (y == floorY && depth[tile] > 1) {
            return floor[tile];
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
