package com.jaguarm.nauvisterrain.test;

import com.jaguarm.nauvisterrain.NauvisTerrain;
import com.jaguarm.nauvisterrain.noise.Evaluator;
import com.jaguarm.nauvisterrain.noise.MapSettings;
import com.jaguarm.nauvisterrain.noise.Terrain;
import com.jaguarm.nauvisterrain.world.Nauvis;
import com.jaguarm.nauvisterrain.world.NauvisGenerator;
import com.jaguarm.nauvisterrain.world.NauvisSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.bus.api.IEventBus;

/** The world type's gametests. */
public final class TerrainGameTests {
    private static final long SEED = 123;

    private TerrainGameTests() {
    }

    public static void register(IEventBus modBus) {
        GameTests tests = new GameTests(NauvisTerrain.MOD_ID, modBus);
        tests.add("a_chunk_is_the_evaluators_tiles", 20, helper -> {
            NauvisGenerator generator = presetGenerator(helper);
            Terrain terrain = new Terrain(new Evaluator(Nauvis.program(), MapSettings.defaults(SEED)));
            NauvisSettings settings = generator.settings();
            int ores = 0;
            for (ChunkPos pos : starting(terrain)) {
                ProtoChunk chunk = generate(helper, generator, pos);
                Terrain.Area area = terrain.area(pos.getMinBlockX(), pos.getMinBlockZ(), 16, 16);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int wx = pos.getMinBlockX() + x;
                        int wz = pos.getMinBlockZ() + z;
                        String tile = area.tile(wx, wz).name();
                        NauvisSettings.Tile blocks = settings.tiles().get(tile);
                        int top = generator.top(wx, wz);
                        BlockState state = chunk.getBlockState(new BlockPos(x, top, z));
                        helper.assertTrue(state == blocks.block() || settings.resources().containsValue(state),
                                "at " + wx + "," + wz + " the top is " + state + " where the evaluator has " + tile);
                        helper.assertTrue(chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) == top,
                                "the surface heightmap is not at the top at " + wx + "," + wz);
                        if (!blocks.block().getFluidState().isEmpty()) {
                            helper.assertTrue(top == settings.surface(), "a pool above the lowest terrace at " + wx + "," + wz);
                        }
                    }
                }
                for (Terrain.Placed placed : area.entities()) {
                    BlockState ore = settings.resources().get(placed.prototype().name());
                    if (ore != null) {
                        int top = generator.top(placed.x(), placed.y());
                        BlockPos at = new BlockPos(placed.x() - pos.getMinBlockX(), top, placed.y() - pos.getMinBlockZ());
                        helper.assertTrue(chunk.getBlockState(at) == ore, "no " + placed.prototype().name() + " at " + at);
                        helper.assertTrue(chunk.getBlockState(at.below()) != ore, "an ore is more than one block deep at " + at);
                        ores++;
                    }
                }
            }
            helper.assertTrue(ores > 0, "no ore in the starting area");
            helper.succeed();
        });
        tests.add("a_cliff_is_a_face_of_cliff_blocks", 20, helper -> {
            NauvisGenerator generator = presetGenerator(helper);
            NauvisSettings settings = generator.settings();
            // Factorio keeps cliffs out of the starting area; walk out east until one stands.
            for (int x = 300; x < 4000; x++) {
                int top = generator.top(x, 0);
                int step = top - generator.top(x + 1, 0);
                if (step == settings.cliff().step()) {
                    ProtoChunk chunk = generate(helper, generator, ChunkPos.containing(new BlockPos(x, 0, 0)));
                    BlockState face = chunk.getBlockState(new BlockPos(x & 15, top - 1, 0));
                    if (face == settings.cliff().block()) {
                        helper.assertTrue(chunk.getBlockState(new BlockPos(x & 15, top, 0)) != face, "a cliff's top is not its tile");
                        helper.succeed();
                        return;
                    }
                }
            }
            helper.fail("no cliff face in 3700 columns east of the start");
        });
        // The gametest server's world is Nauvis (build.gradle, gameTestPacks): full chunks, through features.
        tests.add("the_world_is_nauvis", 200, helper -> {
            ServerLevel level = helper.getLevel();
            helper.assertTrue(level.getChunkSource().getGenerator() instanceof NauvisGenerator,
                    "the gametest world is not Nauvis; is build/gametest-packs there?");
            NauvisGenerator generator = (NauvisGenerator) level.getChunkSource().getGenerator();
            for (int[] at : new int[][]{{3, 5}, {-40, 17}, {250, -130}}) {
                level.getChunk(at[0], at[1]);
                for (int x = 0; x < 16; x += 5) {
                    for (int z = 0; z < 16; z += 5) {
                        int wx = at[0] * 16 + x;
                        int wz = at[1] * 16 + z;
                        BlockPos pos = new BlockPos(wx, generator.top(wx, wz), wz);
                        String tile = generator.tile(wx, wz).name();
                        BlockState expected = generator.settings().tiles().get(tile).block();
                        BlockState state = level.getBlockState(pos);
                        helper.assertTrue(state == expected || generator.settings().resources().containsValue(state),
                                "at " + pos + " the world has " + state + " where Nauvis has " + tile);
                        helper.assertTrue(level.getBlockState(pos.above()).isAir(), "something stands on the ground at " + pos);
                    }
                }
            }
            helper.succeed();
        });
    }

    /** The overworld generator of the world preset, given seed 123. */
    private static NauvisGenerator presetGenerator(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorldPreset preset = level.registryAccess().lookupOrThrow(Registries.WORLD_PRESET)
                .getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.fromNamespaceAndPath(NauvisTerrain.MOD_ID, "nauvis")))
                .value();
        if (!(preset.overworld().orElseThrow().generator() instanceof NauvisGenerator generator)) {
            throw helper.assertionException("the preset's overworld is not Nauvis");
        }
        generator.createState(level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET), randomState(level), SEED);
        return generator;
    }

    private static RandomState randomState(ServerLevel level) {
        return RandomState.create(NoiseGeneratorSettings.dummy(), level.registryAccess().lookupOrThrow(Registries.NOISE), SEED);
    }

    private static ProtoChunk generate(GameTestHelper helper, NauvisGenerator generator, ChunkPos pos) {
        ServerLevel level = helper.getLevel();
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
        generator.fillFromNoise(Blender.empty(), randomState(level), level.structureManager(), chunk).join();
        return chunk;
    }

    /** Chunk 0,0, two more, and the first chunk of the starting area that has ore. */
    private static ChunkPos[] starting(Terrain terrain) {
        for (int cx = -8; cx < 8; cx++) {
            for (int cz = -8; cz < 8; cz++) {
                Terrain.Area area = terrain.area(cx * 16, cz * 16, 16, 16);
                if (area.entities().stream().anyMatch(p -> p.prototype().type().equals("resource"))) {
                    return new ChunkPos[]{new ChunkPos(0, 0), new ChunkPos(-7, 12), new ChunkPos(20, -3), new ChunkPos(cx, cz)};
                }
            }
        }
        return new ChunkPos[]{new ChunkPos(0, 0), new ChunkPos(-7, 12), new ChunkPos(20, -3)};
    }
}
