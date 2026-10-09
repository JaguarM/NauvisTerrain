package com.jaguarm.nauvisterrain.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Dynamic Trees, when it is installed, reached by reflection (docs/ARCHITECTURE.md, the world): a
 * species grown on a ground block as Dynamic Trees' own world generation grows it.
 */
final class DynamicTrees {
    private static final String PACKAGE = "com.dtteam.dynamictrees.";
    private static final Api API = Api.find();

    private DynamicTrees() {
    }

    static boolean installed() {
        return API != null;
    }

    /** Grows a species, by its Dynamic Trees name, on a ground block it roots in; false where it does not grow. */
    static boolean grow(WorldGenLevel level, BlockPos ground, String species, int radius, Direction facing) {
        try {
            return API.grow(level, ground, species, radius, facing);
        } catch (Throwable e) {
            throw new IllegalStateException("Dynamic Trees failed to grow " + species + " at " + ground, e);
        }
    }

    private record Api(Class<?> soil, MethodHandle findSpecies, MethodHandle isValid, MethodHandle acceptsSoil,
                       MethodHandle levelContext, MethodHandle context, MethodHandle generate) {

        /** Dynamic Trees' world generation, or null when it is not installed. */
        static Api find() {
            Class<?> species;
            try {
                species = Class.forName(PACKAGE + "tree.species.Species");
            } catch (ClassNotFoundException e) {
                return null;
            }
            try {
                Class<?> levelContext = Class.forName(PACKAGE + "api.worldgen.LevelContext");
                Class<?> context = Class.forName(PACKAGE + "worldgen.DynamicTreeGenerationContext");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                return new Api(Class.forName(PACKAGE + "block.soil.SoilBlock"),
                        lookup.findStatic(species, "findSpecies", MethodType.methodType(species, String.class)),
                        lookup.findVirtual(species, "isValid", MethodType.methodType(boolean.class)),
                        lookup.findVirtual(species, "isAcceptableSoilForWorldgen",
                                MethodType.methodType(boolean.class, LevelAccessor.class, BlockPos.class, BlockState.class)),
                        lookup.findStatic(levelContext, "create", MethodType.methodType(levelContext, LevelAccessor.class)),
                        lookup.findConstructor(context, MethodType.methodType(void.class, levelContext, species, BlockPos.class,
                                BlockPos.MutableBlockPos.class, Holder.class, Direction.class, int.class, boolean.class)),
                        lookup.findVirtual(species, "generate", MethodType.methodType(boolean.class, context)));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Dynamic Trees is installed, but not as the version Nauvis Terrain knows", e);
            }
        }

        boolean grow(WorldGenLevel level, BlockPos ground, String name, int radius, Direction facing) throws Throwable {
            BlockState state = level.getBlockState(ground);
            if (soil.isInstance(state.getBlock())) {
                return false;
            }
            Object species = findSpecies.invoke(name);
            if (!(boolean) isValid.invoke(species) || !(boolean) acceptsSoil.invoke(species, (LevelAccessor) level, ground, state)) {
                return false;
            }
            Object generation = context.invoke(levelContext.invoke((LevelAccessor) level), species, ground, ground.mutable(),
                    level.getBiome(ground), facing, radius, true);
            return (boolean) generate.invoke(species, generation);
        }
    }
}
