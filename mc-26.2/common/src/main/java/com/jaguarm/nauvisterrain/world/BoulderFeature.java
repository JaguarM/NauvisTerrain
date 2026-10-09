package com.jaguarm.nauvisterrain.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/**
 * A rock as Factorio's: a mound of blocks over the tiles its collision box covers, with one more
 * on top of the middle when it is two high. Each block comes from the provider, so a huge rock can
 * be stone and coal ore mixed.
 */
public final class BoulderFeature extends Feature<BoulderFeature.Configuration> {

    public record Configuration(BlockStateProvider blocks, int width, int length, int height) implements FeatureConfiguration {
        public static final Codec<Configuration> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockStateProvider.CODEC.fieldOf("blocks").forGetter(Configuration::blocks),
                Codec.intRange(1, 8).fieldOf("width").forGetter(Configuration::width),
                Codec.intRange(1, 8).fieldOf("length").forGetter(Configuration::length),
                Codec.intRange(1, 2).optionalFieldOf("height", 1).forGetter(Configuration::height)
        ).apply(i, Configuration::new));
    }

    public BoulderFeature() {
        super(Configuration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<Configuration> context) {
        Configuration config = context.config();
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        boolean placed = false;
        for (int dx = -(config.width() - 1) / 2; dx <= config.width() / 2; dx++) {
            for (int dz = -(config.length() - 1) / 2; dz <= config.length() / 2; dz++) {
                placed |= put(level, context, origin.offset(dx, 0, dz));
            }
        }
        if (config.height() > 1) {
            placed |= put(level, context, origin.above());
        }
        return placed;
    }

    /** One block where there is room for it and ground under it. */
    private boolean put(WorldGenLevel level, FeaturePlaceContext<Configuration> context, BlockPos pos) {
        BlockState here = level.getBlockState(pos);
        if (!(here.isAir() || here.is(BlockTags.REPLACEABLE)) || level.getBlockState(pos.below()).isAir()) {
            return false;
        }
        setBlock(level, pos, context.config().blocks().getState(level, context.random(), pos));
        return true;
    }
}
