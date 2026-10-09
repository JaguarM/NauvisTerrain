package com.jaguarm.nauvisterrain.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;
import com.jaguarm.nauvisterrain.world.Nauvis;
import com.jaguarm.nauvisterrain.world.TerrainBlocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What stands for each of Factorio's trees, rocks and decoratives: a configured feature built of
 * vanilla blocks, and a placed feature under the Factorio name that puts it where Factorio does
 * (`nauvis_terrain:autoplace`). A living tree is a vanilla `minecraft:tree` of vanilla logs and
 * leaves inside a `minecraft:random_selector`, as vanilla lists its trees, so mods that replace
 * vanilla trees (Dynamic Trees) find and replace these.
 */
final class Autoplace {
    private Autoplace() {
    }

    /** A tree's log, leaves (null for a bare one), shape, and the Dynamic Trees species nearest it. */
    private record Tree(String log, String leaves, Shape shape, String species) {
    }

    private enum Shape { SPRUCE, OAK, BUSHY, BIG, SPARSE, STUMP, BARE, FORKED, FALLEN }

    /** Factorio's conifers are spruce-like; its broadleaves oak-like, each in the vanilla leaves nearest its colour. */
    private static final Map<String, Tree> TREES = Map.ofEntries(
            Map.entry("tree-01", new Tree("spruce_log", "spruce_leaves", Shape.SPRUCE, "spruce")),
            Map.entry("tree-02", new Tree("spruce_log", "azalea_leaves", Shape.SPRUCE, "spruce")),
            Map.entry("tree-02-red", new Tree("spruce_log", "cherry_leaves", Shape.SPRUCE, "cherry")),
            Map.entry("tree-03", new Tree("oak_log", "flowering_azalea_leaves", Shape.OAK, "oak")),
            Map.entry("tree-04", new Tree("oak_log", "oak_leaves", Shape.OAK, "oak")),
            Map.entry("tree-05", new Tree("oak_log", "jungle_leaves", Shape.BUSHY, "jungle")),
            Map.entry("tree-06", new Tree("acacia_log", "acacia_leaves", Shape.SPARSE, "acacia")),
            Map.entry("tree-06-brown", new Tree("acacia_log", "pale_oak_leaves", Shape.SPARSE, "acacia")),
            Map.entry("tree-07", new Tree("oak_log", "oak_leaves", Shape.OAK, "oak")),
            Map.entry("tree-08", new Tree("oak_log", "birch_leaves", Shape.OAK, "birch")),
            Map.entry("tree-08-brown", new Tree("dark_oak_log", "dark_oak_leaves", Shape.OAK, "dark_oak")),
            Map.entry("tree-08-red", new Tree("dark_oak_log", "cherry_leaves", Shape.OAK, "cherry")),
            Map.entry("tree-09", new Tree("dark_oak_log", "dark_oak_leaves", Shape.BIG, "dark_oak")),
            Map.entry("tree-09-brown", new Tree("dark_oak_log", "pale_oak_leaves", Shape.BIG, "pale_oak")),
            Map.entry("tree-09-red", new Tree("dark_oak_log", "cherry_leaves", Shape.BIG, "cherry")),
            Map.entry("dry-tree", new Tree("oak_log", null, Shape.STUMP, null)),
            Map.entry("dead-grey-trunk", new Tree("pale_oak_log", null, Shape.BARE, null)),
            Map.entry("dead-tree-desert", new Tree("acacia_log", null, Shape.FORKED, null)),
            Map.entry("dry-hairy-tree", new Tree("birch_log", null, Shape.BARE, null)),
            Map.entry("dead-dry-hairy-tree", new Tree("dark_oak_log", null, Shape.FALLEN, null)));

    /** A rock's blocks: stone, with as much coal ore as stone in the huge one, as Factorio's huge rock yields. */
    private static final Map<String, JsonObject> ROCKS = Map.of(
            "huge-rock", weighted(Map.of("minecraft:stone", 1, "minecraft:coal_ore", 1)),
            "big-rock", state("minecraft:stone"),
            "big-sand-rock", state("minecraft:sandstone"));

    /** The vanilla plant nearest each of Factorio's plant decoratives; its small rocks are stone. */
    private static final Map<String, String> PLANTS = Map.ofEntries(
            Map.entry("green-carpet-grass", "short_grass"), Map.entry("green-small-grass", "short_grass"),
            Map.entry("green-hairy-grass", "tall_grass"), Map.entry("brown-carpet-grass", "short_dry_grass"),
            Map.entry("brown-hairy-grass", "tall_dry_grass"), Map.entry("brown-fluff", "short_dry_grass"),
            Map.entry("brown-fluff-dry", "dead_bush"), Map.entry("green-asterisk", "fern"),
            Map.entry("green-asterisk-mini", "fern"), Map.entry("brown-asterisk", "short_dry_grass"),
            Map.entry("brown-asterisk-mini", "short_dry_grass"), Map.entry("red-asterisk", "dead_bush"),
            Map.entry("green-pita", "bush"), Map.entry("green-pita-mini", "bush"), Map.entry("red-pita", "dead_bush"),
            Map.entry("green-croton", "large_fern"), Map.entry("red-croton", "dead_bush"), Map.entry("garballo", "bush"),
            Map.entry("garballo-mini-dry", "dead_bush"), Map.entry("green-bush-mini", "bush"),
            Map.entry("green-desert-bush", "bush"), Map.entry("red-desert-bush", "dead_bush"),
            Map.entry("white-desert-bush", "dead_bush"), Map.entry("medium-rock", "stone"),
            Map.entry("small-rock", "stone"), Map.entry("tiny-rock", "stone"),
            Map.entry("medium-sand-rock", "stone"), Map.entry("small-sand-rock", "stone"));

    /** The tile each of Factorio's decals paints the ground with. */
    private static final Map<String, String> DECALS = Map.of(
            "sand-decal", "sand-1", "sand-dune-decal", "sand-3", "red-desert-decal", "red-desert-1",
            "light-mud-decal", "dirt-2", "dark-mud-decal", "dirt-6", "cracked-mud-decal", "dry-dirt");

    /**
     * The decals first, rocks and trees next, and the plants last, from the program's last back, each
     * only into an empty block: of the plants Factorio puts on one tile, the program's last stands.
     */
    static List<Prototype> inOrder() {
        List<Prototype> out = new ArrayList<>();
        List<Prototype> things = Nauvis.program().prototypes;
        things.stream().filter(p -> p.kind().equals("decorative") && p.decal()).forEach(out::add);
        things.stream().filter(p -> p.type().equals("simple-entity")).forEach(out::add);
        things.stream().filter(p -> p.type().equals("tree")).forEach(out::add);
        things.reversed().stream().filter(Autoplace::plant).forEach(out::add);
        return out;
    }

    /** A decorative that stands in the block above the ground: a plant, or a small rock. */
    private static boolean plant(Prototype p) {
        return p.kind().equals("decorative") && !p.decal();
    }

    /** Dynamic Trees' trees where Factorio's stand, which grow only with Dynamic Trees installed. */
    static final String DYNAMIC_TREES = Nauvis.MOD_ID + ":dynamic_trees";

    /** The biome's vegetation: decals, rocks and trees, Dynamic Trees' trees, then the plants. */
    static List<String> features() {
        List<String> out = new ArrayList<>();
        inOrder().stream().filter(p -> !plant(p)).forEach(p -> out.add(id(p)));
        out.add(DYNAMIC_TREES);
        inOrder().stream().filter(Autoplace::plant).forEach(p -> out.add(id(p)));
        return out;
    }

    static String id(Prototype p) {
        return Nauvis.MOD_ID + ":" + Nauvis.id(p.name());
    }

    /** Every configured and placed feature, by path under `data/`. */
    static Map<String, JsonObject> files() {
        Map<String, JsonObject> files = new LinkedHashMap<>();
        for (Prototype p : inOrder()) {
            String path = Nauvis.id(p.name()) + ".json";
            files.put(Nauvis.MOD_ID + "/worldgen/feature/" + path, configured(p));
            JsonObject placed = new JsonObject();
            placed.addProperty("feature", id(p));
            JsonArray placement = new JsonArray();
            JsonObject autoplace = new JsonObject();
            autoplace.addProperty("type", Nauvis.MOD_ID + ":autoplace");
            autoplace.addProperty("entity", p.name());
            placement.add(autoplace);
            if (plant(p)) {
                JsonObject air = new JsonObject();
                air.addProperty("type", "minecraft:matching_blocks");
                air.addProperty("blocks", "minecraft:air");
                JsonObject empty = new JsonObject();
                empty.addProperty("type", "minecraft:block_predicate_filter");
                empty.add("predicate", air);
                placement.add(empty);
            }
            placed.add("placement", placement);
            files.put(Nauvis.MOD_ID + "/worldgen/placed_feature/" + path, placed);
        }
        JsonObject species = new JsonObject();
        for (Prototype p : inOrder()) {
            if (p.type().equals("tree") && require(TREES, p).species != null) {
                species.addProperty(p.name(), "dynamictrees:" + require(TREES, p).species);
            }
        }
        JsonObject config = new JsonObject();
        config.add("species", species);
        files.put(Nauvis.MOD_ID + "/worldgen/feature/dynamic_trees.json", feature(DYNAMIC_TREES, config));
        JsonObject placed = new JsonObject();
        placed.addProperty("feature", DYNAMIC_TREES);
        placed.add("placement", new JsonArray());
        files.put(Nauvis.MOD_ID + "/worldgen/placed_feature/dynamic_trees.json", placed);
        return files;
    }

    private static JsonObject configured(Prototype p) {
        if (p.type().equals("tree")) {
            return tree(require(TREES, p));
        }
        if (p.type().equals("simple-entity")) {
            double[] box = p.collisionBox();
            JsonObject config = new JsonObject();
            config.add("blocks", require(ROCKS, p));
            config.addProperty("width", Math.max(1, (int) Math.round(box[2] - box[0])));
            config.addProperty("length", Math.max(1, (int) Math.round(box[3] - box[1])));
            config.addProperty("height", 2);
            return feature(Nauvis.MOD_ID + ":boulder", config);
        }
        if (p.decal()) {
            double[] box = p.collisionBox();
            JsonObject config = new JsonObject();
            config.add("state_provider", state(Nauvis.MOD_ID + ":" + Nauvis.id(require(DECALS, p))));
            JsonObject target = new JsonObject();
            target.addProperty("type", "minecraft:matching_block_tag");
            target.addProperty("tag", Nauvis.MOD_ID + ":ground");
            config.add("target", target);
            config.addProperty("radius", Math.min(8, (int) Math.round(Math.min(box[2] - box[0], box[3] - box[1]) / 2)));
            config.addProperty("half_height", 0);
            return feature("minecraft:disk", config);
        }
        JsonObject config = new JsonObject();
        config.add("to_place", state("minecraft:" + require(PLANTS, p)));
        return feature("minecraft:simple_block", config);
    }

    private static JsonObject tree(Tree tree) {
        if (tree.shape == Shape.FALLEN) {
            JsonObject config = new JsonObject();
            config.add("log_decorators", new JsonArray());
            config.add("log_length", uniform(3, 5));
            config.add("stump_decorators", new JsonArray());
            config.add("trunk_provider", log(tree.log));
            return feature("minecraft:fallen_tree", config);
        }
        JsonObject config = new JsonObject();
        JsonObject below = new JsonObject();
        // The ground under a trunk stays Factorio's tile.
        below.addProperty("type", "minecraft:rule_based");
        below.add("rules", new JsonArray());
        config.add("below_trunk_provider", below);
        config.add("decorators", new JsonArray());
        JsonObject foliage = new JsonObject();
        JsonObject trunk = new JsonObject();
        trunk.addProperty("type", "minecraft:straight_trunk_placer");
        JsonObject minimum = new JsonObject();
        minimum.addProperty("type", "minecraft:two_layers_feature_size");
        switch (tree.shape) {
            case SPRUCE -> {
                foliage.addProperty("type", "minecraft:spruce_foliage_placer");
                foliage.add("offset", uniform(0, 1));
                foliage.add("radius", uniform(1, 2));
                foliage.add("trunk_height", uniform(1, 1));
                trunk(trunk, 4, 2, 0);
                minimum.addProperty("limit", 2);
                minimum.addProperty("upper_size", 2);
            }
            case OAK, BIG, BUSHY -> {
                foliage.addProperty("type", "minecraft:blob_foliage_placer");
                foliage.addProperty("height", tree.shape == Shape.BUSHY ? 2 : 3);
                foliage.addProperty("offset", 0);
                foliage.addProperty("radius", 2);
                trunk(trunk, tree.shape == Shape.BIG ? 5 : tree.shape == Shape.BUSHY ? 3 : 4, tree.shape == Shape.BIG ? 2 : 1, 0);
            }
            case SPARSE -> {
                foliage.addProperty("type", "minecraft:blob_foliage_placer");
                foliage.addProperty("height", 2);
                foliage.addProperty("offset", 0);
                foliage.addProperty("radius", 1);
                trunk(trunk, 3, 1, 0);
            }
            default -> {
                foliage.addProperty("type", "minecraft:blob_foliage_placer");
                foliage.addProperty("height", 0);
                foliage.addProperty("offset", 0);
                foliage.addProperty("radius", 0);
                if (tree.shape == Shape.FORKED) {
                    trunk.addProperty("type", "minecraft:forking_trunk_placer");
                    trunk(trunk, 3, 1, 1);
                } else {
                    trunk(trunk, tree.shape == Shape.STUMP ? 1 : 3, 1, 0);
                }
            }
        }
        config.add("foliage_placer", foliage);
        config.add("foliage_provider", tree.leaves == null ? state("minecraft:air") : leaves(tree.leaves));
        config.addProperty("ignore_vines", true);
        config.add("minimum_size", minimum);
        config.add("trunk_placer", trunk);
        config.add("trunk_provider", log(tree.log));
        JsonObject vanilla = feature("minecraft:tree", config);
        if (tree.leaves == null) {
            return vanilla;
        }
        // One tree, always: a selector of one with the same tree as its default.
        JsonObject placed = new JsonObject();
        placed.add("feature", vanilla);
        placed.add("placement", new JsonArray());
        JsonObject entry = new JsonObject();
        entry.addProperty("chance", 1.0);
        entry.add("feature", placed);
        JsonArray features = new JsonArray();
        features.add(entry);
        JsonObject selector = new JsonObject();
        selector.add("default", placed);
        selector.add("features", features);
        return feature("minecraft:random_selector", selector);
    }

    private static void trunk(JsonObject trunk, int base, int a, int b) {
        trunk.addProperty("base_height", base);
        trunk.addProperty("height_rand_a", a);
        trunk.addProperty("height_rand_b", b);
    }

    private static <T> T require(Map<String, T> table, Prototype p) {
        T found = table.get(p.name());
        if (found == null) {
            throw new IllegalStateException("Nauvis places " + p.name() + ", which nothing stands for here");
        }
        return found;
    }

    private static JsonObject feature(String type, JsonObject config) {
        JsonObject feature = new JsonObject();
        feature.addProperty("type", type);
        config.entrySet().forEach(e -> feature.add(e.getKey(), e.getValue()));
        return feature;
    }

    private static JsonObject state(String block) {
        JsonObject state = new JsonObject();
        state.addProperty("id", block);
        return state;
    }

    private static JsonObject log(String log) {
        JsonObject state = state("minecraft:" + log);
        JsonObject properties = new JsonObject();
        properties.addProperty("axis", "y");
        state.add("properties", properties);
        return state;
    }

    private static JsonObject leaves(String leaves) {
        JsonObject state = state("minecraft:" + leaves);
        JsonObject properties = new JsonObject();
        properties.addProperty("distance", "7");
        properties.addProperty("persistent", "false");
        properties.addProperty("waterlogged", "false");
        state.add("properties", properties);
        return state;
    }

    private static JsonObject weighted(Map<String, Integer> blocks) {
        JsonObject provider = new JsonObject();
        provider.addProperty("type", "minecraft:weighted");
        JsonArray entries = new JsonArray();
        blocks.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            JsonObject entry = new JsonObject();
            entry.add("data", state(e.getKey()));
            entry.addProperty("weight", e.getValue());
            entries.add(entry);
        });
        provider.add("entries", entries);
        return provider;
    }

    private static JsonObject uniform(int min, int max) {
        JsonObject uniform = new JsonObject();
        uniform.addProperty("type", "minecraft:uniform");
        uniform.addProperty("min_inclusive", min);
        uniform.addProperty("max_inclusive", max);
        return uniform;
    }
}
