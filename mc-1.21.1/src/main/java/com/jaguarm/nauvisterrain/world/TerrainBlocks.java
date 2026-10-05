package com.jaguarm.nauvisterrain.world;

import com.jaguarm.nauvisterrain.NauvisTerrain;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * A block for each of Nauvis's ground tiles, under the tile's Factorio name: `grass-1` is
 * `nauvis_terrain:grass_1` (CLAUDE.md, rule 3). A water tile is vanilla water and has no block.
 * And the cliff, which no tool mines: what breaks one is the pack's, through
 * `#nauvis_terrain:cliffs`.
 */
public final class TerrainBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(NauvisTerrain.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(NauvisTerrain.MOD_ID);

    /** Each ground tile's block, by Factorio name. */
    public static final Map<String, DeferredBlock<Block>> TILES;

    /** Factorio's cliff: as hard to remove as bedrock, and it drops nothing. */
    public static final DeferredBlock<Block> CLIFF = BLOCKS.registerSimpleBlock("cliff", BlockBehaviour.Properties.of()
            .mapColor(nearest(Nauvis.program().cliff.mapColor())).strength(-1.0F, 3_600_000.0F).noLootTable()
            .sound(SoundType.STONE));

    static {
        Map<String, DeferredBlock<Block>> tiles = new LinkedHashMap<>();
        for (Prototype p : Nauvis.program().prototypes) {
            if (p.kind().equals("tile") && !Nauvis.isWater(p)) {
                DeferredBlock<Block> block = BLOCKS.<Block>registerBlock(Nauvis.id(p.name()), TileBlock::new, ground(p));
                ITEMS.registerSimpleBlockItem(block);
                tiles.put(p.name(), block);
            }
        }
        TILES = Collections.unmodifiableMap(tiles);
        ITEMS.registerSimpleBlockItem(CLIFF);
    }

    private TerrainBlocks() {
    }

    /** A tile's block, which a tree growing on it leaves as it is rather than turning it to dirt. */
    private static final class TileBlock extends Block {
        TileBlock(Properties properties) {
            super(properties);
        }

        @Override
        public boolean onTreeGrow(BlockState state, LevelReader level, BiConsumer<BlockPos, BlockState> placeFunction,
                                  RandomSource random, BlockPos pos, TreeConfiguration config) {
            return true;
        }
    }

    /** Dirt-hard and shovel work; grass sounds like grass, sand and red desert like sand, dirt like dirt. */
    private static BlockBehaviour.Properties ground(Prototype tile) {
        String name = tile.name();
        SoundType sound = name.startsWith("grass") ? SoundType.GRASS
                : name.startsWith("sand") || name.startsWith("red-desert") ? SoundType.SAND
                : SoundType.GRAVEL;
        return BlockBehaviour.Properties.of().mapColor(nearest(tile.mapColor())).strength(0.5F).sound(sound);
    }

    /** The vanilla map colour closest to a Factorio map colour. */
    private static MapColor nearest(int rgb) {
        MapColor best = MapColor.DIRT;
        long bestDistance = Long.MAX_VALUE;
        for (int id = 1; id < 64; id++) {
            MapColor candidate = MapColor.byId(id);
            if (candidate == MapColor.NONE) {
                continue;
            }
            long dr = ((candidate.col >> 16) & 255) - ((rgb >> 16) & 255);
            long dg = ((candidate.col >> 8) & 255) - ((rgb >> 8) & 255);
            long db = (candidate.col & 255) - (rgb & 255);
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
