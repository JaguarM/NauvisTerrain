package com.jaguarm.nauvisterrain.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

import java.util.Map;

/**
 * Dynamic Trees' trees where Factorio puts its living trees: in each chunk, the species that stands
 * for each (by Factorio name), grown on the ground it stands on, the spots thinned so the crowns
 * have room. Without Dynamic Trees it grows nothing.
 */
public record DynamicTreesFeature(Map<String, String> species) implements Feature {
    public static final MapCodec<DynamicTreesFeature> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("species").forGetter(DynamicTreesFeature::species)
    ).apply(i, DynamicTreesFeature::new));

    /** How far apart two grown trees stand at least, and the room Dynamic Trees fits a crown into. */
    private static final int SPACING = 5;
    private static final int RADIUS = 3;

    @Override
    public MapCodec<DynamicTreesFeature> codec() {
        return CODEC;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator chunkGenerator, RandomSource random, BlockPos origin) {
        if (!DynamicTrees.installed() || !(chunkGenerator instanceof NauvisGenerator generator)) {
            return false;
        }
        boolean grown = false;
        for (Terraces.Standing tree : generator.thinned(species.keySet(), ChunkPos.containing(origin), SPACING)) {
            Terraces.Spot spot = tree.spot();
            grown |= DynamicTrees.grow(level, new BlockPos(spot.x(), spot.y() - 1, spot.z()), species.get(tree.name()),
                    RADIUS, Direction.from2DDataValue(random.nextInt(4)));
        }
        return grown;
    }
}
