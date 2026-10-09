package com.jaguarm.nauvisterrain.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;
import com.jaguarm.nauvisterrain.world.Nauvis;
import com.jaguarm.nauvisterrain.world.TerrainBlocks;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A Dynamic Trees treepack for Nauvis, as data Dynamic Trees reads from every mod's `trees/` and
 * nothing else does: Factorio's ground tiles as soils every tree roots in, as Factorio's trees stand
 * on any of them, and Nauvis's vanilla trees cancelled, so that the trees
 * `nauvis_terrain:dynamic_trees` grows where Factorio's stand are its only ones.
 */
final class DynamicTreesData {
    private DynamicTreesData() {
    }

    /** Whether a tile is ground Dynamic Trees calls sand-like as well as dirt-like: sand and red desert. */
    private static boolean sandLike(String tile) {
        return tile.startsWith("sand") || tile.startsWith("red-desert");
    }

    /**
     * A tile's soil name. Dynamic Trees makes each soil's rooty block in its own namespace, as
     * `dynamictrees:rooty_<name>`, so the name carries Nauvis's to stay clear of other packs'.
     */
    static String soil(Prototype tile) {
        return "nauvis_" + Nauvis.id(tile.name());
    }

    static List<Prototype> soils() {
        return Nauvis.program().prototypes.stream()
                .filter(p -> p.kind().equals("tile") && TerrainBlocks.tiles().containsKey(p.name())).toList();
    }

    /** The treepack, written under `trees/nauvis_terrain/` at the pack's root. */
    record Pack(PackOutput output) implements DataProvider {
        @Override
        public CompletableFuture<?> run(CachedOutput cache) {
            Path root = output.getOutputFolder().resolve("trees").resolve(Nauvis.MOD_ID);
            List<CompletableFuture<?>> saves = new ArrayList<>();
            for (Prototype tile : soils()) {
                JsonObject soil = new JsonObject();
                soil.addProperty("primitive_soil", Nauvis.MOD_ID + ":" + Nauvis.id(tile.name()));
                JsonArray acceptable = new JsonArray();
                acceptable.add("dirt_like");
                if (sandLike(tile.name())) {
                    acceptable.add("sand_like");
                }
                soil.add("acceptable_soils", acceptable);
                saves.add(DataProvider.saveStable(cache, soil,
                        root.resolve("soil_properties").resolve(soil(tile) + ".json")));
            }
            saves.add(DataProvider.saveStable(cache, cancellers(), root.resolve("world_gen/feature_cancellers.json")));
            return CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new));
        }

        @Override
        public String getName() {
            return "Nauvis's Dynamic Trees treepack";
        }


        private static JsonArray cancellers() {
            JsonObject cancel = new JsonObject();
            cancel.addProperty("type", "tree");
            cancel.addProperty("namespace", "minecraft");
            return entry(cancel);
        }

        private static JsonArray entry(JsonObject cancellers) {
            JsonObject select = new JsonObject();
            select.addProperty("name", Nauvis.MOD_ID + ":nauvis");
            JsonObject entry = new JsonObject();
            entry.add("select", select);
            entry.add("cancellers", cancellers);
            JsonArray list = new JsonArray();
            list.add(entry);
            return list;
        }

    }

    /** Each rooty soil's look, under the name Dynamic Trees gives it: its tile under Dynamic Trees' roots. */
    record Models(PackOutput output) implements DataProvider {
        @Override
        public CompletableFuture<?> run(CachedOutput cache) {
            Path blockstates = output.getOutputFolder(PackOutput.Target.RESOURCE_PACK).resolve("dynamictrees").resolve("blockstates");
            List<CompletableFuture<?>> saves = new ArrayList<>();
            for (Prototype tile : soils()) {
                String id = Nauvis.id(tile.name());
                JsonArray multipart = new JsonArray();
                for (String model : new String[]{Nauvis.MOD_ID + ":block/" + id + "_0", "dynamictrees:block/roots"}) {
                    JsonObject apply = new JsonObject();
                    apply.addProperty("model", model);
                    JsonObject part = new JsonObject();
                    part.add("apply", apply);
                    multipart.add(part);
                }
                JsonObject state = new JsonObject();
                state.add("multipart", multipart);
                saves.add(DataProvider.saveStable(cache, state, blockstates.resolve("rooty_" + soil(tile) + ".json")));
            }
            return CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new));
        }

        @Override
        public String getName() {
            return "Nauvis's rooty soils";
        }
    }
}
