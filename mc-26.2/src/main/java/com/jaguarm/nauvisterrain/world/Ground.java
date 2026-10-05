package com.jaguarm.nauvisterrain.world;

import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;
import com.jaguarm.nauvisterrain.noise.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.Map;

/**
 * Nauvis's ground for one world seed in the world preset's blocks: {@link Terraces}' kinds of block,
 * each tile's block on top, and where Factorio puts a resource, its block in place of the top one.
 */
final class Ground {
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    final Terraces terraces;
    private final BlockState cliff;
    private final BlockState[] block;
    private final BlockState[] floor;
    private final Map<String, BlockState> resources;

    Ground(NauvisSettings settings, long worldSeed) {
        this.terraces = new Terraces(settings.map(), worldSeed, settings.surface(), settings.cliff().step(), name -> {
            NauvisSettings.Tile tile = tile(settings, name);
            return tile.block().getFluidState().isEmpty() ? 1 : tile.depth();
        });
        this.cliff = settings.cliff().block();
        this.resources = settings.resources();
        List<Prototype> tiles = terraces.terrain.tiles;
        block = new BlockState[tiles.size()];
        floor = new BlockState[tiles.size()];
        for (int t = 0; t < tiles.size(); t++) {
            NauvisSettings.Tile tile = tile(settings, tiles.get(t).name());
            block[t] = tile.block();
            floor[t] = tile.floor().orElse(STONE);
        }
    }

    private static NauvisSettings.Tile tile(NauvisSettings settings, String name) {
        NauvisSettings.Tile tile = settings.tiles().get(name);
        if (tile == null) {
            throw new IllegalArgumentException("the world preset gives no block for the tile " + name);
        }
        return tile;
    }

    /** The ground's blocks in a chunk, with its two worldgen heightmaps. */
    void fill(ChunkAccess chunk) {
        int x0 = chunk.getPos().getMinBlockX();
        int z0 = chunk.getPos().getMinBlockZ();
        Terrain.Area area = terraces.terrain.area(x0, z0, 16, 16);
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int minY = chunk.getMinY();
        int[][] tops = new int[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                Terraces.Column column = terraces.column(area, x0 + x, z0 + z);
                tops[x][z] = column.top();
                for (int y = minY; y <= Math.min(column.top(), chunk.getMaxY()); y++) {
                    BlockState state = state(column, x0 + x, y, z0 + z, minY);
                    if (state != AIR) {
                        chunk.getSection(chunk.getSectionIndex(y)).setBlockState(x, y & 15, z, state, false);
                    }
                }
                int tile = column.tile();
                boolean liquid = terraces.liquid(tile);
                oceanFloor.update(x, liquid ? terraces.surface - terraces.depth(tile) : column.top(), z,
                        liquid ? floor[tile] : block[tile]);
                worldSurface.update(x, column.top(), z, block[tile]);
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
        Terraces.Column column = terraces.column(x, z);
        for (int y = column.top(); y >= minY; y--) {
            if (type.isOpaque().test(state(column, x, y, z, minY))) {
                return y + 1;
            }
        }
        return minY;
    }

    /** The whole column from the bottom of the world up. */
    BlockState[] column(int x, int z, int minY, int height) {
        Terraces.Column column = terraces.column(x, z);
        BlockState[] states = new BlockState[height];
        for (int i = 0; i < height; i++) {
            states[i] = state(column, x, minY + i, z, minY);
        }
        return states;
    }

    /** Where Factorio places a tree, rock or decorative in a chunk: on top of each tile it stands on. */
    List<BlockPos> placed(String name, ChunkPos chunk) {
        return terraces.placed(name, chunk.x(), chunk.z()).stream().map(s -> new BlockPos(s.x(), s.y(), s.z())).toList();
    }

    private BlockState state(Terraces.Column column, int x, int y, int z, int minY) {
        return switch (terraces.layer(column, x, y, z, minY)) {
            case AIR -> AIR;
            case TILE -> block[column.tile()];
            case CLIFF -> cliff;
            case FLOOR -> floor[column.tile()];
            case STONE -> STONE;
            case DEEPSLATE -> DEEPSLATE;
            case BEDROCK -> BEDROCK;
        };
    }
}
