package com.jaguarm.nauvisterrain.world;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;

import java.util.function.BiConsumer;

/** A ground tile's block, which a tree growing on it leaves as it is rather than turning it to dirt. */
public final class TileBlock extends Block {
    public TileBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean onTreeGrow(BlockState state, LevelReader level, BiConsumer<BlockPos, BlockState> placeFunction,
                              RandomSource random, BlockPos pos, TreeConfiguration config) {
        return true;
    }
}
