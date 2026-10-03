package com.jaguarm.nauvisterrain.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jaguarm.nauvisterrain.NauvisTerrain;
import com.jaguarm.nauvisterrain.noise.Evaluator;
import com.jaguarm.nauvisterrain.noise.MapSettings;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;
import com.jaguarm.nauvisterrain.noise.Terrain;
import com.jaguarm.nauvisterrain.world.Nauvis;
import com.jaguarm.nauvisterrain.world.TerrainBlocks;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * A Dynamic Trees treepack for Nauvis, as data Dynamic Trees reads from every mod's `trees/` and
 * nothing else does: Factorio's ground tiles as soils trees root in, Nauvis's trees cancelled, and
 * Dynamic Trees' own grown in their place, in the mix of species Factorio's trees come to. With it
 * Dynamic Trees spaces the trees by its own rules, not Factorio's.
 */
final class DynamicTreesData {
    /** The seeds and the square around 0,0 whose trees give the mix of species. */
    private static final long[] SAMPLE_SEEDS = {1, 2, 3, 4};
    private static final int SAMPLE_SIZE = 1024;

    private DynamicTreesData() {
    }

    /** Whether a tile is ground Dynamic Trees calls dirt-like, as grass and dirt are; sand and red desert are sand-like. */
    private static boolean dirtLike(String tile) {
        return !(tile.startsWith("sand") || tile.startsWith("red-desert"));
    }

    static List<Prototype> soils() {
        return Nauvis.program().prototypes.stream()
                .filter(p -> p.kind().equals("tile") && TerrainBlocks.TILES.containsKey(p.name())).toList();
    }

    /** The treepack, written under `trees/nauvis_terrain/` at the pack's root. */
    record Pack(PackOutput output) implements DataProvider {
        @Override
        public CompletableFuture<?> run(CachedOutput cache) {
            Path root = output.getOutputFolder().resolve("trees").resolve(NauvisTerrain.MOD_ID);
            List<CompletableFuture<?>> saves = new ArrayList<>();
            for (Prototype tile : soils()) {
                JsonObject soil = new JsonObject();
                soil.addProperty("primitive_soil", NauvisTerrain.MOD_ID + ":" + TerrainBlocks.id(tile.name()));
                JsonArray acceptable = new JsonArray();
                acceptable.add(dirtLike(tile.name()) ? "dirt_like" : "sand_like");
                soil.add("acceptable_soils", acceptable);
                saves.add(DataProvider.saveStable(cache, soil,
                        root.resolve("soil_properties").resolve(TerrainBlocks.id(tile.name()) + ".json")));
            }
            saves.add(DataProvider.saveStable(cache, populator(), root.resolve("world_gen/default.json")));
            saves.add(DataProvider.saveStable(cache, cancellers(), root.resolve("world_gen/feature_cancellers.json")));
            return CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new));
        }

        @Override
        public String getName() {
            return "Nauvis's Dynamic Trees treepack";
        }

        private static JsonArray populator() {
            JsonObject random = new JsonObject();
            species().forEach(random::addProperty);
            JsonObject species = new JsonObject();
            species.add("random", random);
            JsonObject apply = new JsonObject();
            apply.add("species", species);
            apply.addProperty("density", "noise()");
            apply.addProperty("chance", 1.0);
            return entry(apply, null);
        }

        private static JsonArray cancellers() {
            JsonObject cancel = new JsonObject();
            cancel.addProperty("type", "tree");
            cancel.addProperty("namespace", "minecraft");
            return entry(null, cancel);
        }

        private static JsonArray entry(JsonObject apply, JsonObject cancellers) {
            JsonObject select = new JsonObject();
            select.addProperty("name", NauvisTerrain.MOD_ID + ":nauvis");
            JsonObject entry = new JsonObject();
            entry.add("select", select);
            if (apply != null) {
                entry.add("apply", apply);
            }
            if (cancellers != null) {
                entry.add("cancellers", cancellers);
            }
            JsonArray list = new JsonArray();
            list.add(entry);
            return list;
        }

        /** How many trees of each species Factorio places over the sample, by species. */
        private static Map<String, Integer> species() {
            Map<String, Integer> counts = new TreeMap<>();
            for (long seed : SAMPLE_SEEDS) {
                Terrain terrain = new Terrain(new Evaluator(Nauvis.program(), MapSettings.defaults(seed)));
                for (Terrain.Placed placed : terrain.area(-SAMPLE_SIZE / 2, -SAMPLE_SIZE / 2, SAMPLE_SIZE, SAMPLE_SIZE).entities()) {
                    if (placed.prototype().type().equals("tree")) {
                        String species = Autoplace.species(placed.prototype());
                        if (species != null) {
                            counts.merge(species, 1, Integer::sum);
                        }
                    }
                }
            }
            return counts;
        }
    }

    /** Each rooty soil's look: its tile under Dynamic Trees' roots. */
    record Models(PackOutput output) implements DataProvider {
        @Override
        public CompletableFuture<?> run(CachedOutput cache) {
            Path blockstates = output.getOutputFolder(PackOutput.Target.RESOURCE_PACK).resolve(NauvisTerrain.MOD_ID).resolve("blockstates");
            List<CompletableFuture<?>> saves = new ArrayList<>();
            for (Prototype tile : soils()) {
                String id = TerrainBlocks.id(tile.name());
                JsonArray multipart = new JsonArray();
                for (String model : new String[]{NauvisTerrain.MOD_ID + ":block/" + id + "_0", "dynamictrees:block/roots"}) {
                    JsonObject apply = new JsonObject();
                    apply.addProperty("model", model);
                    JsonObject part = new JsonObject();
                    part.add("apply", apply);
                    multipart.add(part);
                }
                JsonObject state = new JsonObject();
                state.add("multipart", multipart);
                saves.add(DataProvider.saveStable(cache, state, blockstates.resolve("rooty_" + id + ".json")));
            }
            return CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new));
        }

        @Override
        public String getName() {
            return "Nauvis's rooty soils";
        }
    }
}
