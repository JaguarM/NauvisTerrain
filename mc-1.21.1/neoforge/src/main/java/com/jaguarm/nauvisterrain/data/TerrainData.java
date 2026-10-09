package com.jaguarm.nauvisterrain.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;
import com.jaguarm.nauvisterrain.world.Nauvis;
import com.jaguarm.nauvisterrain.world.TerrainBlocks;
import com.jaguarm.nauvisterrain.world.Terraces;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.common.data.LanguageProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Everything about the world type that is data: block models, textures and names, loot and tags,
 * the biome and the world preset, all from the noise program (docs/ARCHITECTURE.md, the world).
 */
@EventBusSubscriber(modid = Nauvis.MOD_ID)
public final class TerrainData {
    /** How deep each water tile gets at most, and what it lies on. */
    private static final Map<String, String[]> WATER = Map.of(
            "water", new String[]{"3", "minecraft:sand"},
            "deepwater", new String[]{"8", "minecraft:gravel"});
    /** How many textures tools/make_textures.py makes of each tile and of the cliff. */
    private static final int TEXTURE_VARIANTS = 4;
    /** The vanilla block each Factorio resource is (CLAUDE.md, rule 4); uranium and oil have none. */
    private static final Map<String, String> RESOURCES = Map.of(
            "iron-ore", "minecraft:iron_ore",
            "copper-ore", "minecraft:copper_ore",
            "coal", "minecraft:coal_ore",
            "stone", "minecraft:stone");
    /** The underground ores and stones the biome keeps: vanilla's, less the three Factorio places on the surface. */
    private static final List<String> UNDERGROUND_ORES = List.of(
            "ore_dirt", "ore_gravel", "ore_granite_upper", "ore_granite_lower", "ore_diorite_upper", "ore_diorite_lower",
            "ore_andesite_upper", "ore_andesite_lower", "ore_tuff", "ore_gold", "ore_gold_lower", "ore_redstone",
            "ore_redstone_lower", "ore_diamond", "ore_diamond_medium", "ore_diamond_large", "ore_diamond_buried",
            "ore_lapis", "ore_lapis_buried");

    private TerrainData() {
    }

    @SubscribeEvent
    static void gather(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        ExistingFileHelper files = event.getExistingFileHelper();
        CompletableFuture<HolderLookup.Provider> lookup = event.getLookupProvider();
        generator.addProvider(event.includeClient(), new Models(output, files));
        generator.addProvider(event.includeClient(), new DynamicTreesData.Models(output));
        generator.addProvider(event.includeClient(), new Lang(output));
        generator.addProvider(event.includeServer(), new LootTableProvider(output, Set.of(),
                List.of(new LootTableProvider.SubProviderEntry(Loot::new, LootContextParamSets.BLOCK)), lookup));
        generator.addProvider(event.includeServer(), new Tags(output, lookup, files));
        generator.addProvider(event.includeServer(), new Worldgen(output));
        generator.addProvider(event.includeServer(), new DynamicTreesData.Pack(output));
    }

    private static List<Prototype> groundTiles() {
        return Nauvis.program().prototypes.stream()
                .filter(p -> p.kind().equals("tile") && TerrainBlocks.tiles().containsKey(p.name())).toList();
    }

    private static class Models extends BlockStateProvider {
        Models(PackOutput output, ExistingFileHelper files) {
            super(output, Nauvis.MOD_ID, files);
        }

        @Override
        protected void registerStatesAndModels() {
            TerrainBlocks.tiles().values().forEach(block -> varied(block.value(), block.unwrapKey().orElseThrow().location().getPath()));
            varied(TerrainBlocks.cliff().value(), TerrainBlocks.cliff().unwrapKey().orElseThrow().location().getPath());
        }

        /** Four textures, tools/make_textures.py's, each at four turns, picked at random per block. */
        private void varied(Block block, String name) {
            List<ConfiguredModel> variants = new ArrayList<>();
            ModelFile first = null;
            for (int k = 0; k < TEXTURE_VARIANTS; k++) {
                ModelFile model = models().cubeAll(name + "_" + k, modLoc("block/" + name + "_" + k));
                for (int turn = 0; turn < 360; turn += 90) {
                    variants.add(new ConfiguredModel(model, 0, turn, false));
                }
                if (first == null) {
                    first = model;
                }
            }
            getVariantBuilder(block).partialState().setModels(variants.toArray(ConfiguredModel[]::new));
            simpleBlockItem(block, first);
        }
    }

    private static class Lang extends LanguageProvider {
        Lang(PackOutput output) {
            super(output, Nauvis.MOD_ID, "en_us");
        }

        @Override
        protected void addTranslations() {
            for (Prototype tile : groundTiles()) {
                add(TerrainBlocks.tiles().get(tile.name()).value(), tile.title());
            }
            add(TerrainBlocks.cliff().value(), Nauvis.program().cliff.title());
            add("generator.nauvis_terrain.nauvis", "Nauvis");
            for (Prototype tile : DynamicTreesData.soils()) {
                add("block.dynamictrees.rooty_" + DynamicTreesData.soil(tile), "Rooty " + tile.title().toLowerCase(java.util.Locale.ROOT));
            }
            add("nauvis_terrain.map.title", "Nauvis map generator");
            add("nauvis_terrain.map.preset", "Preset");
            add("nauvis_terrain.map.custom", "Custom");
            add("nauvis_terrain.map.none", "None");
        }
    }

    private static class Loot extends BlockLootSubProvider {
        Loot(HolderLookup.Provider registries) {
            super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
        }

        @Override
        protected void generate() {
            TerrainBlocks.tiles().values().forEach(block -> dropSelf(block.value()));
        }

        @Override
        protected Iterable<Block> getKnownBlocks() {
            return TerrainBlocks.tiles().values().stream().map(Holder::value)::iterator;
        }
    }



    /** Shovel work; grass and dirt count as vanilla's dirt and sand as its sand, so what grows there grows here. */
    private static class Tags extends BlockTagsProvider {
        Tags(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup, ExistingFileHelper files) {
            super(output, lookup, Nauvis.MOD_ID, files);
        }

        @Override
        protected void addTags(HolderLookup.Provider registries) {
            var shovel = tag(BlockTags.MINEABLE_WITH_SHOVEL);
            var dirt = tag(BlockTags.DIRT);
            var sand = tag(BlockTags.SAND);
            TerrainBlocks.tiles().forEach((name, block) -> {
                shovel.add(block.unwrapKey().orElseThrow());
                (name.startsWith("sand") || name.startsWith("red-desert") ? sand : dirt).add(block.unwrapKey().orElseThrow());
            });
            tag(TerrainBlocks.CLIFFS).add(TerrainBlocks.cliff().unwrapKey().orElseThrow());
            var ground = tag(TerrainBlocks.GROUND);
            TerrainBlocks.tiles().values().forEach(block -> ground.add(block.unwrapKey().orElseThrow()));
        }
    }

    /** The biome, its place among the overworld's, and the world preset that uses them. */
    private record Worldgen(PackOutput output) implements DataProvider {
        @Override
        public CompletableFuture<?> run(CachedOutput cache) {
            Path data = output.getOutputFolder(PackOutput.Target.DATA_PACK);
            Map<String, JsonElement> files = new LinkedHashMap<>();
            files.put("nauvis_terrain/worldgen/biome/nauvis.json", biome());
            files.put("nauvis_terrain/worldgen/world_preset/nauvis.json", preset());
            files.put("minecraft/tags/worldgen/biome/is_overworld.json", tag("nauvis_terrain:nauvis"));
            files.put("minecraft/tags/worldgen/world_preset/normal.json", tag("nauvis_terrain:nauvis"));
            files.putAll(Autoplace.files());
            List<CompletableFuture<?>> saves = new ArrayList<>();
            files.forEach((path, json) -> saves.add(DataProvider.saveStable(cache, json, data.resolve(path))));
            return CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new));
        }

        @Override
        public String getName() {
            return "Nauvis worldgen";
        }

        private static JsonObject tag(String value) {
            JsonObject tag = new JsonObject();
            JsonArray values = new JsonArray();
            values.add(value);
            tag.add("values", values);
            return tag;
        }

        /**
         * Plains' climate and mobs, Factorio's water with cod where Factorio has fish, no carvers,
         * and on the surface only Factorio's trees, rocks and decoratives.
         */
        private static JsonObject biome() {
            Prototype water = Nauvis.program().prototypes.stream().filter(p -> p.name().equals("water")).findFirst().orElseThrow();
            JsonObject biome = new JsonObject();
            biome.add("carvers", new JsonObject());
            biome.addProperty("downfall", 0.4);
            JsonObject effects = new JsonObject();
            effects.addProperty("fog_color", 0xc0d8ff);
            JsonObject mood = new JsonObject();
            mood.addProperty("block_search_extent", 8);
            mood.addProperty("offset", 2.0);
            mood.addProperty("sound", "minecraft:ambient.cave");
            mood.addProperty("tick_delay", 6000);
            effects.add("mood_sound", mood);
            effects.addProperty("sky_color", 0x78a7ff);
            effects.addProperty("water_color", water.effectColor());
            effects.addProperty("water_fog_color", 0x050533);
            biome.add("effects", effects);
            JsonArray features = new JsonArray();
            for (int step = 0; step < 11; step++) {
                JsonArray inStep = new JsonArray();
                if (step == 2) {
                    inStep.add("minecraft:amethyst_geode");
                }
                if (step == 6) {
                    UNDERGROUND_ORES.forEach(ore -> inStep.add("minecraft:" + ore));
                }
                if (step == 9) {
                    Autoplace.inOrder().forEach(p -> inStep.add(Autoplace.id(p)));
                }
                features.add(inStep);
            }
            biome.add("features", features);
            biome.addProperty("has_precipitation", true);
            biome.add("spawn_costs", new JsonObject());
            biome.add("spawners", spawners());
            biome.addProperty("temperature", 0.8);
            return biome;
        }

        private static JsonObject spawners() {
            JsonObject spawners = new JsonObject();
            spawners.add("ambient", spawns(new Object[][]{{"bat", 8, 8, 10}}));
            spawners.add("axolotls", new JsonArray());
            spawners.add("creature", spawns(new Object[][]{{"sheep", 4, 4, 12}, {"pig", 4, 4, 10}, {"chicken", 4, 4, 10},
                    {"cow", 4, 4, 8}, {"horse", 2, 6, 5}, {"donkey", 1, 3, 1}}));
            spawners.add("misc", new JsonArray());
            spawners.add("monster", spawns(new Object[][]{{"spider", 4, 4, 100}, {"zombie", 4, 4, 95},
                    {"zombie_villager", 1, 1, 5}, {"skeleton", 4, 4, 100}, {"creeper", 4, 4, 100}, {"slime", 4, 4, 100},
                    {"enderman", 1, 4, 10}, {"witch", 1, 1, 5}}));
            spawners.add("underground_water_creature", spawns(new Object[][]{{"glow_squid", 4, 6, 10}}));
            spawners.add("water_ambient", spawns(new Object[][]{{"cod", 3, 6, 15}}));
            spawners.add("water_creature", new JsonArray());
            return spawners;
        }

        private static JsonArray spawns(Object[][] entries) {
            JsonArray list = new JsonArray();
            for (Object[] e : entries) {
                JsonObject spawn = new JsonObject();
                spawn.addProperty("type", "minecraft:" + e[0]);
                spawn.addProperty("maxCount", (Integer) e[2]);
                spawn.addProperty("minCount", (Integer) e[1]);
                spawn.addProperty("weight", (Integer) e[3]);
                list.add(spawn);
            }
            return list;
        }

        /** Vanilla's normal world with Nauvis for the overworld. */
        private static JsonObject preset() {
            JsonObject tiles = new JsonObject();
            for (Prototype tile : Nauvis.program().prototypes) {
                if (!tile.kind().equals("tile")) {
                    continue;
                }
                JsonObject entry = new JsonObject();
                String[] water = WATER.get(tile.name());
                if (water != null) {
                    entry.add("block", state("minecraft:water"));
                    entry.addProperty("depth", Integer.parseInt(water[0]));
                    entry.add("floor", state(water[1]));
                } else {
                    entry.add("block", state(Nauvis.MOD_ID + ":" + Nauvis.id(tile.name())));
                }
                tiles.add(tile.name(), entry);
            }
            JsonObject resources = new JsonObject();
            for (Prototype p : Nauvis.program().prototypes) {
                if (p.type().equals("resource")) {
                    String block = RESOURCES.get(p.name());
                    if (block == null) {
                        throw new IllegalStateException("Nauvis places " + p.name() + ", which has no vanilla block here");
                    }
                    resources.add(p.name(), state(block));
                }
            }
            JsonObject settings = new JsonObject();
            settings.addProperty("surface", Terraces.SURFACE);
            settings.add("tiles", tiles);
            settings.add("resources", resources);
            JsonObject cliff = new JsonObject();
            cliff.add("block", state(Nauvis.MOD_ID + ":cliff"));
            cliff.addProperty("step", Terraces.STEP);
            settings.add("cliff", cliff);
            JsonObject biomeSource = new JsonObject();
            biomeSource.addProperty("type", "minecraft:fixed");
            biomeSource.addProperty("biome", "nauvis_terrain:nauvis");
            JsonObject generator = new JsonObject();
            generator.addProperty("type", "nauvis_terrain:nauvis");
            generator.add("biome_source", biomeSource);
            generator.add("settings", settings);
            JsonObject overworld = new JsonObject();
            overworld.addProperty("type", "minecraft:overworld");
            overworld.add("generator", generator);

            JsonObject dimensions = new JsonObject();
            dimensions.add("minecraft:overworld", overworld);
            dimensions.add("minecraft:the_end", vanilla("minecraft:the_end", "minecraft:end",
                    biomeSource("minecraft:the_end", null)));
            dimensions.add("minecraft:the_nether", vanilla("minecraft:the_nether", "minecraft:nether",
                    biomeSource("minecraft:multi_noise", "minecraft:nether")));
            JsonObject preset = new JsonObject();
            preset.add("dimensions", dimensions);
            return preset;
        }

        private static JsonObject state(String block) {
            JsonObject state = new JsonObject();
            state.addProperty("Name", block);
            return state;
        }

        private static JsonObject biomeSource(String type, String preset) {
            JsonObject source = new JsonObject();
            source.addProperty("type", type);
            if (preset != null) {
                source.addProperty("preset", preset);
            }
            return source;
        }

        private static JsonObject vanilla(String type, String settings, JsonObject biomeSource) {
            JsonObject generator = new JsonObject();
            generator.addProperty("type", "minecraft:noise");
            generator.add("biome_source", biomeSource);
            generator.addProperty("settings", settings);
            JsonObject dimension = new JsonObject();
            dimension.addProperty("type", type);
            dimension.add("generator", generator);
            return dimension;
        }
    }
}
