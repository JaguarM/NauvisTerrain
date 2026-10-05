package com.jaguarm.nauvisterrain.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

import java.util.stream.Stream;

/**
 * Where Factorio places one tree, rock or decorative in the chunk: on top of the ground at each
 * tile it stands on (docs/ARCHITECTURE.md, autoplace). A placed feature with this as its placement
 * puts its configured feature, a vanilla tree or anything else, exactly there. Outside a Nauvis
 * world it places nothing.
 */
public final class AutoplacePlacement extends PlacementModifier {
    public static final MapCodec<AutoplacePlacement> CODEC = Codec.STRING.fieldOf("entity")
            .xmap(AutoplacePlacement::new, p -> p.entity);
    public static final PlacementModifierType<AutoplacePlacement> TYPE = () -> CODEC;

    private final String entity;

    public AutoplacePlacement(String entity) {
        this.entity = entity;
    }

    @Override
    public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos origin) {
        if (!(context.generator() instanceof NauvisGenerator generator)) {
            return Stream.empty();
        }
        return generator.placed(entity, new ChunkPos(origin)).stream();
    }

    @Override
    public PlacementModifierType<?> type() {
        return TYPE;
    }
}
