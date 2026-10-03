package com.jaguarm.nauvisterrain.world;

import com.jaguarm.nauvisterrain.NauvisTerrain;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A block for each of Nauvis's ground tiles, under the tile's Factorio name: `grass-1` is
 * `nauvis_terrain:grass_1` (CLAUDE.md, rule 3). A water tile is vanilla water and has no block.
 */
public final class TerrainBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(NauvisTerrain.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(NauvisTerrain.MOD_ID);

    /** Each ground tile's block, by Factorio name. */
    public static final Map<String, DeferredBlock<Block>> TILES;

    static {
        Map<String, DeferredBlock<Block>> tiles = new LinkedHashMap<>();
        for (Prototype p : Nauvis.program().prototypes) {
            if (p.kind().equals("tile") && !isWater(p)) {
                DeferredBlock<Block> block = BLOCKS.registerSimpleBlock(id(p.name()), properties -> ground(p, properties));
                ITEMS.registerSimpleBlockItem(block);
                tiles.put(p.name(), block);
            }
        }
        TILES = Collections.unmodifiableMap(tiles);
    }

    private TerrainBlocks() {
    }

    /** A Factorio name as a Minecraft path. */
    public static String id(String factorioName) {
        return factorioName.replace('-', '_');
    }

    /** A tile that Factorio's collision layers call water. */
    public static boolean isWater(Prototype tile) {
        return tile.collisionMask().layers().contains("water_tile");
    }

    /** Dirt-hard and shovel work; grass sounds like grass, sand and red desert like sand, dirt like dirt. */
    private static BlockBehaviour.Properties ground(Prototype tile, BlockBehaviour.Properties properties) {
        String name = tile.name();
        SoundType sound = name.startsWith("grass") ? SoundType.GRASS
                : name.startsWith("sand") || name.startsWith("red-desert") ? SoundType.SAND
                : SoundType.GRAVEL;
        return properties.mapColor(nearest(tile.mapColor())).strength(0.5F).sound(sound);
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
