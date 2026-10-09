package com.jaguarm.nauvisterrain.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/**
 * A rock as Factorio's: a mound of blocks over the tiles its collision box covers, with one more
 * on top of the middle when it is two high. Each block comes from the provider, so a huge rock can
 * be stone and coal ore mixed.
 */
public record BoulderFeature(Holder<BlockStateProvider> blocks, int width, int length, int height) implements Feature {
    public static final MapCodec<BoulderFeature> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BlockStateProvider.CODEC.fieldOf("blocks").forGetter(BoulderFeature::blocks),
            Codec.intRange(1, 8).fieldOf("width").forGetter(BoulderFeature::width),
            Codec.intRange(1, 8).fieldOf("length").forGetter(BoulderFeature::length),
            Codec.intRange(1, 2).optionalFieldOf("height", 1).forGetter(BoulderFeature::height)
    ).apply(i, BoulderFeature::new));

    @Override
    public MapCodec<BoulderFeature> codec() {
        return CODEC;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        boolean placed = false;
        for (int dx = -(width - 1) / 2; dx <= width / 2; dx++) {
            for (int dz = -(length - 1) / 2; dz <= length / 2; dz++) {
                placed |= put(level, random, origin.offset(dx, 0, dz));
            }
        }
        if (height > 1) {
            placed |= put(level, random, origin.above());
        }
        return placed;
    }

    /** One block where there is room for it and ground under it. */
    private boolean put(WorldGenLevel level, RandomSource random, BlockPos pos) {
        BlockState here = level.getBlockState(pos);
        if (!(here.isAir() || here.is(BlockTags.REPLACEABLE)) || level.getBlockState(pos.below()).isAir()) {
            return false;
        }
        setBlock(level, pos, blocks.value().getState(level, random, pos));
        return true;
    }
}
