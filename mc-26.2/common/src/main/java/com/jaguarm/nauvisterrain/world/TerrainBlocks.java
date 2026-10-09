package com.jaguarm.nauvisterrain.world;

import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * A block for each of Nauvis's ground tiles, under the tile's Factorio name: `grass-1` is
 * `nauvis_terrain:grass_1` (CLAUDE.md, rule 3). A water tile is vanilla water and has no block.
 * And the cliff, which no tool mines: what breaks one is the pack's, through
 * `#nauvis_terrain:cliffs`. Each loader registers them through {@link #register}.
 */
public final class TerrainBlocks {
    /** The tile blocks, which a decal paints over. */
    public static final TagKey<Block> GROUND = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(Nauvis.MOD_ID, "ground"));
    /** What breaks a cliff targets this tag: a pack's cliff explosives, say. */
    public static final TagKey<Block> CLIFFS = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(Nauvis.MOD_ID, "cliffs"));

    private static Map<String, Holder<Block>> tiles = Map.of();
    private static Holder<Block> cliff;

    /** How a loader registers a block and its item, under a path, from properties set up by `properties`. */
    public interface Registrar {
        Holder<Block> block(String path, UnaryOperator<BlockBehaviour.Properties> properties);
    }

    private TerrainBlocks() {
    }

    public static void register(Registrar registrar) {
        Map<String, Holder<Block>> blocks = new LinkedHashMap<>();
        for (Prototype p : Nauvis.program().prototypes) {
            if (p.kind().equals("tile") && !Nauvis.isWater(p)) {
                blocks.put(p.name(), registrar.block(Nauvis.id(p.name()), properties -> ground(p, properties)));
            }
        }
        tiles = Collections.unmodifiableMap(blocks);
        cliff = registrar.block("cliff", properties -> properties.mapColor(nearest(Nauvis.program().cliff.mapColor()))
                .strength(-1.0F, 3_600_000.0F).noLootTable().sound(SoundType.STONE));
    }

    /** Each ground tile's block, by Factorio name. */
    public static Map<String, Holder<Block>> tiles() {
        return tiles;
    }

    /** Factorio's cliff: as hard to remove as bedrock, and it drops nothing. */
    public static Holder<Block> cliff() {
        return cliff;
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
