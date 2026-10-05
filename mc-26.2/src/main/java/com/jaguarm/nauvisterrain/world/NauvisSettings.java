package com.jaguarm.nauvisterrain.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Optional;

/**
 * The world preset's settings for the generator: the y of the lowest terrace's top block, for each
 * Factorio tile the block that stands for it, for each resource the block that replaces the ground
 * where Factorio puts it, the cliffs' block and step (docs/ARCHITECTURE.md, what a pack changes),
 * and the map generator screen's sliders. A resource with no block is not placed.
 */
public record NauvisSettings(int surface, Map<String, Tile> tiles, Map<String, BlockState> resources, Cliff cliff,
                             NauvisMap map) {

    /** The same world with other map generator settings. */
    public NauvisSettings withMap(NauvisMap map) {
        return new NauvisSettings(surface, tiles, resources, cliff, map);
    }

    /** A cliff's face block, and how many blocks one cliff level rises. */
    public record Cliff(BlockState block, int step) {
        public static final Codec<Cliff> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.fieldOf("block").forGetter(Cliff::block),
                Codec.intRange(1, 32).optionalFieldOf("step", Terraces.STEP).forGetter(Cliff::step)
        ).apply(i, Cliff::new));
    }

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

    /** The map generator screen's settings, as a world saves them. */
    private static final Codec<NauvisMap> MAP = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("sliders", Map.of()).forGetter(NauvisMap::sliders),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("properties", Map.of()).forGetter(NauvisMap::properties),
            Codec.DOUBLE.optionalFieldOf("cliff_smoothing", 0.0).forGetter(NauvisMap::cliffSmoothing)
    ).apply(i, NauvisMap::new));

    public static final Codec<NauvisSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("surface", Terraces.SURFACE).forGetter(NauvisSettings::surface),
            Codec.unboundedMap(Codec.STRING, Tile.CODEC).fieldOf("tiles").forGetter(NauvisSettings::tiles),
            Codec.unboundedMap(Codec.STRING, BlockState.CODEC).optionalFieldOf("resources", Map.of())
                    .forGetter(NauvisSettings::resources),
            Cliff.CODEC.fieldOf("cliff").forGetter(NauvisSettings::cliff),
            MAP.optionalFieldOf("map", NauvisMap.NORMAL).forGetter(NauvisSettings::map)
    ).apply(i, NauvisSettings::new));
}
