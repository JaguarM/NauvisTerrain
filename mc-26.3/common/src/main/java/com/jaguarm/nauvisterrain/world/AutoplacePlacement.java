package com.jaguarm.nauvisterrain.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import java.util.function.Consumer;

/**
 * Where Factorio places one tree, rock or decorative in the chunk: on top of the ground at each
 * tile it stands on (docs/ARCHITECTURE.md, autoplace). A placed feature with this as its placement
 * puts its configured feature, a vanilla tree or anything else, exactly there. Outside a Nauvis
 * world it places nothing.
 */
public record AutoplacePlacement(String entity) implements PlacementModifier {
    public static final MapCodec<AutoplacePlacement> CODEC = Codec.STRING.fieldOf("entity")
            .xmap(AutoplacePlacement::new, AutoplacePlacement::entity);

    @Override
    public void modify(PlacementContext context, RandomSource random, BlockPos origin, Consumer<BlockPos> output) {
        if (context.generator() instanceof NauvisGenerator generator) {
            generator.placed(entity, ChunkPos.containing(origin)).forEach(output);
        }
    }

    @Override
    public MapCodec<AutoplacePlacement> codec() {
        return CODEC;
    }
}
