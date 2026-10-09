package com.jaguarm.nauvisterrain.fabric.mixin;

import com.jaguarm.nauvisterrain.world.TerrainBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;

/** A tile's block stays itself under a growing tree's trunk instead of turning to dirt. */
@Mixin(TrunkPlacer.class)
abstract class TrunkPlacerMixin {
    @Inject(method = "setDirtAt", at = @At("HEAD"), cancellable = true)
    private static void nauvis_terrain$keepTile(LevelSimulatedReader level, BiConsumer<BlockPos, BlockState> setter,
                                                RandomSource random, BlockPos pos, TreeConfiguration config, CallbackInfo info) {
        if (level.isStateAtPosition(pos, state -> state.is(TerrainBlocks.GROUND))) {
            info.cancel();
        }
    }
}
