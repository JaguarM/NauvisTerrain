package com.jaguarm.nauvisterrain.noise;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The noise program tools/gen_terrain.py writes: numbered nodes, named roots, and the prototypes it places. */
public final class NoiseProgram {
    public static final String NAUVIS = "/nauvis_terrain/noise/nauvis.json";

    /** A node's operation; the generator's names in upper case. */
    public enum Op {
        CONST, INPUT, POINTS, PROPERTY,
        ADD, SUB, MUL, DIV, MOD, FMOD, POW, LT, LE, GT, GE, EQ, NE, AND, XOR, OR, NEG, NOT,
        ABS, CEIL, FLOOR, COS, SIN, SQRT, LOG2, ATAN2, CLAMP, IF, MIN, MAX,
        BASIS_NOISE, MULTIOCTAVE_NOISE, VARIABLE_PERSISTENCE_MULTIOCTAVE_NOISE, QUICK_MULTIOCTAVE_NOISE,
        DISTANCE_FROM_NEAREST_POINT, DISTANCE_FROM_NEAREST_POINT_X, DISTANCE_FROM_NEAREST_POINT_Y,
        RANDOM_PENALTY, SPOT_NOISE, EXPRESSION_IN_RANGE
    }

    /**
     * One node: its operation and argument nodes, with a constant's value, an input's or a points
     * list's name, or a property's name and the node for each value the map settings may give it.
     */
    public record Node(Op op, int[] args, double value, String name, Map<String, Integer> variants) {
    }

    /**
     * Factorio's collision layers. A thing cannot stand where its layers meet a tile's, and two
     * things cannot overlap where their layers meet, unless both do not collide with themselves
     * and have the same layers, or either collides with tiles only.
     */
    public record Mask(Set<String> layers, boolean tilesOnly, boolean notCollidingWithItself) {
        public boolean blockedBy(Mask tile) {
            return !Collections.disjoint(layers, tile.layers);
        }

        public boolean collidesWith(Mask other) {
            if (tilesOnly || other.tilesOnly) {
                return false;
            }
            if (notCollidingWithItself && other.notCollidingWithItself && layers.equals(other.layers)) {
                return false;
            }
            return !Collections.disjoint(layers, other.layers);
        }
    }

    /**
     * A tile, entity or decorative the planet places. A box is left, top, right, bottom around the
     * thing's centre; a colour is 0xRRGGBB, or -1 for none.
     */
    public record Prototype(String kind, String type, String name, String order, String control,
                            int placementDensity, boolean hasRichness, double[] collisionBox, Mask collisionMask,
                            int mapColor) {
        public String probabilityRoot() {
            return kind + ":" + name + ":probability";
        }

        public String richnessRoot() {
            return kind + ":" + name + ":richness";
        }
    }

    /** The cliff prototype: its grid, offset, box, layers and colour. */
    public record Cliff(String name, String control, double gridWidth, double gridHeight,
                        double offsetX, double offsetY, double[] collisionBox, Mask collisionMask, int mapColor) {
    }

    public final String factorio;
    public final Node[] nodes;
    public final Map<String, Integer> roots;
    public final List<Prototype> prototypes;
    public final Cliff cliff;
    /** The map preview's colour for things without one of their own, as 0xAARRGGBB. */
    public final Map<String, Integer> chartColors;

    private NoiseProgram(JsonObject json) {
        factorio = json.get("factorio").getAsString();
        JsonArray rawNodes = json.getAsJsonArray("nodes");
        nodes = new Node[rawNodes.size()];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = node(rawNodes.get(i).getAsJsonArray());
        }
        Map<String, Integer> rootMap = new LinkedHashMap<>();
        json.getAsJsonObject("roots").entrySet().forEach(e -> rootMap.put(e.getKey(), e.getValue().getAsInt()));
        roots = Collections.unmodifiableMap(rootMap);
        List<Prototype> list = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("prototypes")) {
            JsonObject p = e.getAsJsonObject();
            list.add(new Prototype(p.get("kind").getAsString(), p.get("type").getAsString(), p.get("name").getAsString(),
                    p.get("order").getAsString(), p.get("control").isJsonNull() ? null : p.get("control").getAsString(),
                    p.get("placement_density").getAsInt(), p.get("richness").getAsBoolean(),
                    box(p.get("collision_box")), mask(p.getAsJsonObject("collision_mask")), colour(p.get("map_color"))));
        }
        prototypes = List.copyOf(list);
        JsonObject c = json.getAsJsonObject("cliff");
        JsonArray grid = c.getAsJsonArray("grid_size");
        JsonArray offset = c.getAsJsonArray("grid_offset");
        cliff = new Cliff(c.get("name").getAsString(), c.get("control").getAsString(),
                grid.get(0).getAsDouble(), grid.get(1).getAsDouble(),
                offset.get(0).getAsDouble(), offset.get(1).getAsDouble(), box(c.get("collision_box")),
                mask(c.getAsJsonObject("collision_mask")), colour(c.get("map_color")));
        Map<String, Integer> chart = new LinkedHashMap<>();
        json.getAsJsonObject("chart_colors").entrySet().forEach(e -> {
            JsonArray rgba = e.getValue().getAsJsonArray();
            int alpha = rgba.size() > 3 ? (int) Math.round(rgba.get(3).getAsDouble() * 255) : 255;
            chart.put(e.getKey(), alpha << 24 | colour(rgba));
        });
        chartColors = Collections.unmodifiableMap(chart);
    }

    public static NoiseProgram load(Reader reader) {
        return new NoiseProgram(JsonParser.parseReader(reader).getAsJsonObject());
    }

    /** Nauvis's program, from the jar. */
    public static NoiseProgram nauvis() {
        try (InputStream in = NoiseProgram.class.getResourceAsStream(NAUVIS)) {
            if (in == null) {
                throw new IllegalStateException(NAUVIS + " is missing; python tools/gen_terrain.py --write makes it");
            }
            return load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public int root(String name) {
        Integer n = roots.get(name);
        if (n == null) {
            throw new IllegalArgumentException("the program has no root " + name);
        }
        return n;
    }

    private static Node node(JsonArray a) {
        Op op = Op.valueOf(a.get(0).getAsString().toUpperCase(Locale.ROOT));
        return switch (op) {
            case CONST -> new Node(op, new int[0], number(a.get(1)), null, null);
            case INPUT, POINTS -> new Node(op, new int[0], 0, a.get(1).getAsString(), null);
            case PROPERTY -> {
                Map<String, Integer> variants = new LinkedHashMap<>();
                a.get(2).getAsJsonObject().entrySet().forEach(e -> variants.put(e.getKey(), e.getValue().getAsInt()));
                int[] args = variants.values().stream().mapToInt(Integer::intValue).toArray();
                yield new Node(op, args, 0, a.get(1).getAsString(), Collections.unmodifiableMap(variants));
            }
            default -> {
                int[] args = new int[a.size() - 1];
                for (int i = 0; i < args.length; i++) {
                    args[i] = a.get(i + 1).getAsInt();
                }
                yield new Node(op, args, 0, null, null);
            }
        };
    }

    private static double number(JsonElement e) {
        if (e.getAsJsonPrimitive().isString()) {
            return switch (e.getAsString()) {
                case "inf" -> Double.POSITIVE_INFINITY;
                case "-inf" -> Double.NEGATIVE_INFINITY;
                default -> throw new IllegalArgumentException("not a number: " + e);
            };
        }
        return e.getAsDouble();
    }

    private static double[] box(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        JsonArray a = e.getAsJsonArray();
        JsonArray lt = a.get(0).getAsJsonArray();
        JsonArray rb = a.get(1).getAsJsonArray();
        return new double[]{lt.get(0).getAsDouble(), lt.get(1).getAsDouble(), rb.get(0).getAsDouble(), rb.get(1).getAsDouble()};
    }

    private static Mask mask(JsonObject m) {
        Set<String> layers = new HashSet<>();
        m.getAsJsonArray("layers").forEach(l -> layers.add(l.getAsString()));
        return new Mask(Set.copyOf(layers), m.get("tiles_only").getAsBoolean(),
                m.get("not_colliding_with_itself").getAsBoolean());
    }

    private static int colour(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return -1;
        }
        JsonArray a = e.getAsJsonArray();
        return a.get(0).getAsInt() << 16 | a.get(1).getAsInt() << 8 | a.get(2).getAsInt();
    }
}
