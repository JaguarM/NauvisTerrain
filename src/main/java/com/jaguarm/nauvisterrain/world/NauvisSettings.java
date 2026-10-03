package com.jaguarm.nauvisterrain.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Optional;

/**
 * The world preset's settings for the generator: the y of the land's top block, and for each
 * Factorio tile the block that stands for it (docs/ARCHITECTURE.md, what a pack changes).
 */
public record NauvisSettings(int surface, Map<String, Tile> tiles) {

    /**
     * One tile's blocks. A ground tile is its block on top of the land; a liquid tile is a pool of
     * its block `depth` deep, cut into the land, on a bed of `floor`.
     */
    public record Tile(BlockState block, int depth, Optional<BlockState> floor) {
        public static final Codec<Tile> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.fieldOf("block").forGetter(Tile::block),
                Codec.intRange(1, 64).optionalFieldOf("depth", 1).forGetter(Tile::depth),
                BlockState.CODEC.optionalFieldOf("floor").forGetter(Tile::floor)
        ).apply(i, Tile::new));
    }

    public static final Codec<NauvisSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("surface", 64).forGetter(NauvisSettings::surface),
            Codec.unboundedMap(Codec.STRING, Tile.CODEC).fieldOf("tiles").forGetter(NauvisSettings::tiles)
    ).apply(i, NauvisSettings::new));
}
