package com.jaguarm.nauvisterrain.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

import java.util.Map;

/**
 * Dynamic Trees' trees where Factorio puts its living trees: in each chunk, the species that stands
 * for each, grown on the ground it stands on, the spots thinned so the crowns have room. Without
 * Dynamic Trees it grows nothing.
 */
public final class DynamicTreesFeature extends Feature<DynamicTreesFeature.Configuration> {
    /** How far apart two grown trees stand at least, and the room Dynamic Trees fits a crown into. */
    private static final int SPACING = 5;
    private static final int RADIUS = 3;

    /** The Dynamic Trees species that stands for each of Factorio's living trees, by Factorio name. */
    public record Configuration(Map<String, String> species) implements FeatureConfiguration {
        public static final Codec<Configuration> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("species").forGetter(Configuration::species)
        ).apply(i, Configuration::new));
    }

    public DynamicTreesFeature() {
        super(Configuration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<Configuration> context) {
        if (!DynamicTrees.installed() || !(context.chunkGenerator() instanceof NauvisGenerator generator)) {
            return false;
        }
        Map<String, String> species = context.config().species();
        boolean grown = false;
        for (Terraces.Standing tree : generator.thinned(species.keySet(), ChunkPos.containing(context.origin()), SPACING)) {
            Terraces.Spot spot = tree.spot();
            grown |= DynamicTrees.grow(context.level(), new BlockPos(spot.x(), spot.y() - 1, spot.z()), species.get(tree.name()),
                    RADIUS, Direction.from2DDataValue(context.random().nextInt(4)));
        }
        return grown;
    }
}
