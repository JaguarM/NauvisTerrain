package com.jaguarm.nauvisterrain.test;

import com.jaguarm.nauvisterrain.NauvisTerrain;
import com.jaguarm.nauvisterrain.noise.Evaluator;
import com.jaguarm.nauvisterrain.noise.MapSettings;
import com.jaguarm.nauvisterrain.noise.Terrain;
import com.jaguarm.nauvisterrain.world.Nauvis;
import com.jaguarm.nauvisterrain.world.NauvisGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.bus.api.IEventBus;

/** The world type's gametests. */
public final class TerrainGameTests {
    private TerrainGameTests() {
    }

    public static void register(IEventBus modBus) {
        GameTests tests = new GameTests(NauvisTerrain.MOD_ID, modBus);
        tests.add("a_chunk_is_the_evaluators_tiles", 20, helper -> {
            ServerLevel level = helper.getLevel();
            WorldPreset preset = level.registryAccess().lookupOrThrow(Registries.WORLD_PRESET)
                    .getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.fromNamespaceAndPath(NauvisTerrain.MOD_ID, "nauvis")))
                    .value();
            LevelStem overworld = preset.overworld().orElseThrow();
            helper.assertTrue(overworld.generator() instanceof NauvisGenerator, "the preset's overworld is not Nauvis");
            NauvisGenerator generator = (NauvisGenerator) overworld.generator();
            long seed = 123;
            RandomState randomState = RandomState.create(NoiseGeneratorSettings.dummy(), level.registryAccess().lookupOrThrow(Registries.NOISE), seed);
            generator.createState(level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET), randomState, seed);
            Terrain terrain = new Terrain(new Evaluator(Nauvis.program(), MapSettings.defaults(seed)));
            int surface = generator.settings().surface();
            for (ChunkPos pos : new ChunkPos[]{new ChunkPos(0, 0), new ChunkPos(-7, 12), new ChunkPos(20, -3)}) {
                ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
                generator.fillFromNoise(Blender.empty(), randomState, level.structureManager(), chunk).join();
                Terrain.Area area = terrain.area(pos.getMinBlockX(), pos.getMinBlockZ(), 16, 16);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        String tile = area.tile(pos.getMinBlockX() + x, pos.getMinBlockZ() + z).name();
                        BlockState expected = generator.settings().tiles().get(tile).block();
                        BlockState top = chunk.getBlockState(new BlockPos(x, surface, z));
                        helper.assertTrue(top == expected, "at " + pos + " " + x + "," + z + " the top is " + top
                                + " where the evaluator has " + tile);
                        helper.assertTrue(chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) == surface,
                                "the surface heightmap is not at the surface");
                    }
                }
            }
            helper.succeed();
        });
        // The gametest server's world is Nauvis (build.gradle, gameTestPacks): full chunks, through features.
        tests.add("the_world_is_nauvis", 200, helper -> {
            ServerLevel level = helper.getLevel();
            helper.assertTrue(level.getChunkSource().getGenerator() instanceof NauvisGenerator,
                    "the gametest world is not Nauvis; is build/gametest-packs there?");
            NauvisGenerator generator = (NauvisGenerator) level.getChunkSource().getGenerator();
            int surface = generator.settings().surface();
            for (int[] at : new int[][]{{3, 5}, {-40, 17}, {250, -130}}) {
                level.getChunk(at[0], at[1]);
                for (int x = 0; x < 16; x += 5) {
                    for (int z = 0; z < 16; z += 5) {
                        BlockPos pos = new BlockPos(at[0] * 16 + x, surface, at[1] * 16 + z);
                        String tile = generator.tile(pos.getX(), pos.getZ()).name();
                        BlockState expected = generator.settings().tiles().get(tile).block();
                        helper.assertTrue(level.getBlockState(pos) == expected,
                                "at " + pos + " the world has " + level.getBlockState(pos) + " where Nauvis has " + tile);
                        helper.assertTrue(level.getBlockState(pos.above()).isAir(), "something stands on the ground at " + pos);
                    }
                }
            }
            helper.succeed();
        });
    }
}
