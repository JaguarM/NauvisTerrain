package com.jaguarm.nauvisterrain.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;
import com.jaguarm.nauvisterrain.world.Nauvis;
import com.jaguarm.nauvisterrain.world.TerrainBlocks;
import com.jaguarm.nauvisterrain.world.Terraces;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.renderer.block.dispatch.Variant;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
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
    static void client(GatherDataEvent.Client event) {
        event.createProvider(Models::new);
        event.createProvider(DynamicTreesData.Models::new);
        event.createProvider((PackOutput output) -> new Lang(output));
    }

    @SubscribeEvent
    static void server(GatherDataEvent.Server event) {
        event.createProvider(Tags::new);
        event.createProvider(Worldgen::new);
        event.createProvider(DynamicTreesData.Pack::new);
    }

    private static List<Prototype> groundTiles() {
        return Nauvis.program().prototypes.stream()
                .filter(p -> p.kind().equals("tile") && TerrainBlocks.tiles().containsKey(p.name())).toList();
    }

    private static class Models extends ModelProvider {
        Models(PackOutput output) {
            super(output, Nauvis.MOD_ID);
        }

        @Override
        protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
            TerrainBlocks.tiles().values().forEach(block -> varied(blockModels, block.value()));
            varied(blockModels, TerrainBlocks.cliff().value());
        }

        /** Four textures, tools/make_textures.py's, each at four turns, picked at random per block. */
        private static void varied(BlockModelGenerators blockModels, Block block) {
            List<Variant> variants = new ArrayList<>();
            Identifier first = null;
            for (int k = 0; k < TEXTURE_VARIANTS; k++) {
                Identifier model = ModelTemplates.CUBE_ALL.createWithSuffix(block, "_" + k,
                        TextureMapping.cube(TextureMapping.getBlockTexture(block, "_" + k)), blockModels.modelOutput);
                Variant variant = BlockModelGenerators.plainModel(model);
                variants.add(variant);
                variants.add(variant.with(BlockModelGenerators.Y_ROT_90));
                variants.add(variant.with(BlockModelGenerators.Y_ROT_180));
                variants.add(variant.with(BlockModelGenerators.Y_ROT_270));
                if (first == null) {
                    first = model;
                }
            }
            blockModels.blockStateOutput.accept(MultiVariantGenerator.dispatch(block,
                    BlockModelGenerators.variants(variants.toArray(Variant[]::new))));
            blockModels.registerSimpleItemModel(block.asItem(), first);
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

    /** Shovel work; grass and dirt count as vanilla's dirt and sand as its sand, so what grows there grows here. */
    private static class Tags extends BlockTagsProvider {
        Tags(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
            super(output, lookup, Nauvis.MOD_ID);
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
            TerrainBlocks.tiles().values().forEach(block -> {
                String id = block.unwrapKey().orElseThrow().identifier().getPath();
                files.put(Nauvis.MOD_ID + "/loot_table/blocks/" + id + ".json", dropSelf(Nauvis.MOD_ID + ":" + id));
            });
            List<CompletableFuture<?>> saves = new ArrayList<>();
            files.forEach((path, json) -> saves.add(DataProvider.saveStable(cache, json, data.resolve(path))));
            return CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new));
        }

        @Override
        public String getName() {
            return "Nauvis worldgen";
        }

        /** A block's loot: itself, unless an explosion destroys it. */
        private static JsonObject dropSelf(String item) {
            JsonObject survives = new JsonObject();
            survives.addProperty("type", "minecraft:survives_explosion");
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "minecraft:item");
            entry.addProperty("name", item);
            JsonArray entries = new JsonArray();
            entries.add(entry);
            JsonObject pool = new JsonObject();
            pool.add("condition", survives);
            pool.add("entries", entries);
            pool.addProperty("rolls", 1);
            JsonArray pools = new JsonArray();
            pools.add(pool);
            JsonObject table = new JsonObject();
            table.addProperty("type", "minecraft:block");
            table.add("pools", pools);
            table.addProperty("random_sequence", item.replace(":", ":blocks/"));
            return table;
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
            JsonObject attributes = new JsonObject();
            JsonObject spawns = new JsonObject();
            spawns.add("argument", spawners());
            spawns.addProperty("modifier", "overlay");
            attributes.add("minecraft:gameplay/natural_mob_spawns", spawns);
            attributes.addProperty("minecraft:visual/sky_color", "#78a7ff");
            biome.add("attributes", attributes);
            biome.add("carvers", new JsonArray());
            biome.addProperty("downfall", 0.4);
            JsonObject effects = new JsonObject();
            effects.addProperty("water_color", String.format("#%06x", water.effectColor()));
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
                    Autoplace.features().forEach(inStep::add);
                }
                features.add(inStep);
            }
            biome.add("features", features);
            biome.addProperty("has_precipitation", true);
            biome.addProperty("temperature", 0.8);
            return biome;
        }

        private static JsonObject spawners() {
            JsonObject byCategory = new JsonObject();
            byCategory.add("ambient", spawns(new Object[][]{{"bat", 8, 8, 10}}));
            byCategory.add("creature", spawns(new Object[][]{{"sheep", 4, 4, 12}, {"pig", 4, 4, 10}, {"chicken", 4, 4, 10},
                    {"cow", 4, 4, 8}, {"horse", 2, 6, 5}, {"donkey", 1, 3, 1}}));
            byCategory.add("monster", spawns(new Object[][]{{"spider", 4, 4, 100}, {"zombie", 4, 4, 90},
                    {"zombie_villager", 1, 1, 5}, {"zombie_horse", 1, 1, 5}, {"skeleton", 4, 4, 100}, {"creeper", 4, 4, 100},
                    {"slime", 4, 4, 100}, {"enderman", 1, 4, 10}, {"witch", 1, 1, 5}}));
            byCategory.add("underground_water_creature", spawns(new Object[][]{{"glow_squid", 4, 6, 10}}));
            byCategory.add("water_ambient", spawns(new Object[][]{{"cod", 3, 6, 15}}));
            JsonObject spawners = new JsonObject();
            spawners.add("spawn_costs", new JsonObject());
            spawners.add("spawns_by_category", byCategory);
            return spawners;
        }

        private static JsonArray spawns(Object[][] entries) {
            JsonArray list = new JsonArray();
            for (Object[] e : entries) {
                JsonObject spawn = new JsonObject();
                spawn.addProperty("type", "minecraft:" + e[0]);
                if (e[1].equals(e[2])) {
                    spawn.addProperty("count", (Integer) e[1]);
                } else {
                    JsonObject count = new JsonObject();
                    count.addProperty("type", "minecraft:uniform");
                    count.addProperty("max_inclusive", (Integer) e[2]);
                    count.addProperty("min_inclusive", (Integer) e[1]);
                    spawn.add("count", count);
                }
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

        private static JsonElement state(String block) {
            return new JsonPrimitive(block);
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
