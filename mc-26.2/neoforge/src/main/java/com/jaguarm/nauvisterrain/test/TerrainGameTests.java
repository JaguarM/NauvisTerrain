package com.jaguarm.nauvisterrain.test;

import com.jaguarm.nauvisterrain.noise.Evaluator;
import com.jaguarm.nauvisterrain.noise.MapSettings;
import com.jaguarm.nauvisterrain.noise.NoiseProgram;
import com.jaguarm.nauvisterrain.noise.Terrain;
import com.jaguarm.nauvisterrain.world.Nauvis;
import com.jaguarm.nauvisterrain.world.NauvisGenerator;
import com.jaguarm.nauvisterrain.world.NauvisMap;
import com.jaguarm.nauvisterrain.world.NauvisSettings;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.bus.api.IEventBus;

import java.util.List;
import java.util.Locale;

/** The world type's gametests. */
public final class TerrainGameTests {
    private static final long SEED = 123;

    private TerrainGameTests() {
    }

    public static void register(IEventBus modBus) {
        GameTests tests = new GameTests(Nauvis.MOD_ID, modBus);
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
        tests.add("trees_stand_where_factorio_puts_them", 400, helper -> {
            ServerLevel level = helper.getLevel();
            if (!(level.getChunkSource().getGenerator() instanceof NauvisGenerator generator)) {
                throw helper.assertionException("the gametest world is not Nauvis");
            }
            List<String> trees = Nauvis.program().prototypes.stream().filter(p -> p.type().equals("tree"))
                    .map(p -> p.name()).toList();
            ChunkPos forest = null;
            int most = 0;
            for (int cx = -24; cx <= 24; cx += 3) {
                for (int cz = -24; cz <= 24; cz += 3) {
                    ChunkPos pos = new ChunkPos(cx, cz);
                    int count = trees.stream().mapToInt(t -> generator.placed(t, pos).size()).sum();
                    if (count > most) {
                        most = count;
                        forest = pos;
                    }
                }
            }
            helper.assertTrue(forest != null && most >= 10, "no forest within 24 chunks of the start");
            level.getChunk(forest.x(), forest.z());
            int standing = 0;
            for (String tree : trees) {
                for (BlockPos at : generator.placed(tree, forest)) {
                    if (level.getBlockState(at).is(BlockTags.LOGS)) {
                        standing++;
                    }
                }
            }
            helper.assertTrue(standing >= most * 3 / 4, standing + " of " + most + " trees stand in " + forest);
            helper.succeed();
        });
        tests.add("a_preset_is_saved_and_builds_its_map", 20, helper -> {
            ServerLevel level = helper.getLevel();
            NauvisGenerator base = presetGenerator(helper);
            NoiseProgram.Preset lakes = Nauvis.program().presets.stream().filter(p -> p.name().equals("lakes")).findFirst()
                    .orElseThrow(() -> helper.assertionException("Factorio's lakes preset is missing"));
            NauvisMap map = new NauvisMap(lakes.sliders(), lakes.properties(), lakes.cliffSmoothing());
            NauvisGenerator generator = new NauvisGenerator(base.getBiomeSource(), base.settings().withMap(map));
            var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
            JsonElement saved = NauvisGenerator.CODEC.codec().encodeStart(ops, generator).getOrThrow();
            NauvisGenerator loaded = NauvisGenerator.CODEC.codec().parse(ops, saved).getOrThrow();
            helper.assertTrue(loaded.settings().map().equals(map), "the map settings did not survive saving: " + saved);
            loaded.createState(level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET), randomState(level), SEED);
            Terrain terrain = new Terrain(new Evaluator(Nauvis.program(), map.settings(SEED)));
            ChunkPos pos = new ChunkPos(9, -4);
            ProtoChunk chunk = generate(helper, loaded, pos);
            Terrain.Area area = terrain.area(pos.getMinBlockX(), pos.getMinBlockZ(), 16, 16);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int wx = pos.getMinBlockX() + x;
                    int wz = pos.getMinBlockZ() + z;
                    BlockState expected = loaded.settings().tiles().get(area.tile(wx, wz).name()).block();
                    BlockState top = chunk.getBlockState(new BlockPos(x, loaded.top(wx, wz), z));
                    helper.assertTrue(top == expected || loaded.settings().resources().containsValue(top),
                            "with the lakes preset the top at " + wx + "," + wz + " is " + top);
                }
            }
            helper.succeed();
        });
        // Generates 20 by 20 full chunks here and in vanilla's Nether, asked for one at a time from
        // the server thread, and logs how fast each went; a measure, not a limit.
        tests.add("chunks_per_second", 2400, helper -> {
            ServerLevel nauvis = helper.getLevel();
            ServerLevel nether = nauvis.getServer().getLevel(Level.NETHER);
            for (ServerLevel level : new ServerLevel[]{nauvis, nether}) {
                long start = System.nanoTime();
                for (int cx = 0; cx < 20; cx++) {
                    for (int cz = 0; cz < 20; cz++) {
                        level.getChunk(2000 + cx, -3000 + cz);
                    }
                }
                double seconds = (System.nanoTime() - start) / 1e9;
                LogUtils.getLogger().info("{}: 400 full chunks in {} s, {} chunks per second", level.dimension().identifier(),
                        String.format(Locale.ROOT, "%.2f", seconds), String.format(Locale.ROOT, "%.0f", 400 / seconds));
            }
            helper.succeed();
        });
        // Dynamic Trees cancels a biome's trees by finding a random selector of minecraft:tree features.
        tests.add("living_trees_are_what_dynamic_trees_cancels", 20, helper -> {
            var configured = helper.getLevel().registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE);
            for (String tree : new String[]{"tree_01", "tree_08_brown", "tree_09_red"}) {
                var feature = configured.getValue(Identifier.fromNamespaceAndPath(Nauvis.MOD_ID, tree));
                helper.assertTrue(feature != null && feature.config() instanceof RandomFeatureConfiguration selector
                        && selector.features().getFirst().feature().value().getFeatures().findFirst().orElseThrow().value()
                        .config() instanceof TreeConfiguration, tree + " is not a selector of one vanilla tree");
            }
            helper.succeed();
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
                .getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.fromNamespaceAndPath(Nauvis.MOD_ID, "nauvis")))
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
