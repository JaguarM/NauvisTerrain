package com.jaguarm.nauvisterrain.noise;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluatorTest {
    static NoiseProgram program;

    @BeforeAll
    static void load() {
        program = NoiseProgram.nauvis();
    }

    static float[][] grid(int x0, int y0, int size) {
        float[] xs = new float[size * size];
        float[] ys = new float[size * size];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = x0 + i % size;
            ys[i] = y0 + i / size;
        }
        return new float[][]{xs, ys};
    }

    @Test
    void everyRootIsFactoriosBitForBit() throws IOException {
        JsonObject fixture;
        try (InputStream in = EvaluatorTest.class.getResourceAsStream("/factorio-values.json")) {
            fixture = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonArray positions = fixture.getAsJsonArray("positions");
        float[] xs = new float[positions.size()];
        float[] ys = new float[positions.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = positions.get(i).getAsJsonArray().get(0).getAsFloat();
            ys[i] = positions.get(i).getAsJsonArray().get(1).getAsFloat();
        }
        for (Map.Entry<String, JsonElement> seed : fixture.getAsJsonObject("seeds").entrySet()) {
            JsonObject expected = seed.getValue().getAsJsonObject();
            String[] roots = expected.keySet().toArray(new String[0]);
            float[][] values = new Evaluator(program, MapSettings.defaults(Long.parseLong(seed.getKey()))).evaluate(roots, xs, ys);
            for (int r = 0; r < roots.length; r++) {
                JsonArray theirs = expected.getAsJsonArray(roots[r]);
                for (int i = 0; i < xs.length; i++) {
                    float want = factorio(theirs.get(i));
                    float got = values[r][i];
                    assertTrue(Float.floatToIntBits(want) == Float.floatToIntBits(got),
                            roots[r] + " at seed " + seed.getKey() + ", " + xs[i] + ", " + ys[i] + ": Factorio " + want + ", ours " + got);
                }
            }
        }
    }

    @Test
    void theMapAroundTheStartIsFactorios() throws IOException {
        JsonObject fixture;
        try (InputStream in = EvaluatorTest.class.getResourceAsStream("/factorio-chunks.json")) {
            fixture = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonArray a = fixture.getAsJsonArray("area");
        int x0 = a.get(0).getAsInt(), y0 = a.get(1).getAsInt(), w = a.get(2).getAsInt(), h = a.get(3).getAsInt();
        Terrain.Area area = new Terrain(new Evaluator(program, MapSettings.defaults(fixture.get("seed").getAsLong())))
                .area(x0, y0, w, h);
        List<String> tiles = new ArrayList<>();
        for (JsonElement run : fixture.getAsJsonArray("tiles")) {
            for (int i = 0; i < run.getAsJsonArray().get(1).getAsInt(); i++) {
                tiles.add(run.getAsJsonArray().get(0).getAsString());
            }
        }
        List<String> ours = new ArrayList<>();
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                ours.add(area.tile(x, y).name());
            }
        }
        assertEquals(tiles, ours);
        assertEquals(strings(fixture.getAsJsonArray("entities")), area.entities().stream()
                .map(p -> p.prototype().name() + " " + p.x() + " " + p.y() + " "
                        + (p.prototype().type().equals("resource") ? (long) p.richness() : 0)).sorted().toList());
        assertEquals(strings(fixture.getAsJsonArray("decoratives")), area.decoratives().stream()
                .map(p -> p.prototype().name() + " " + p.x() + " " + p.y() + " " + (int) p.richness()).sorted().toList());
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        array.forEach(e -> out.add(e.getAsString()));
        return out;
    }

    private static float factorio(JsonElement e) {
        if (e.getAsJsonPrimitive().isString()) {
            return switch (e.getAsString()) {
                case "inf" -> Float.POSITIVE_INFINITY;
                case "-inf" -> Float.NEGATIVE_INFINITY;
                default -> Float.NaN;
            };
        }
        return e.getAsFloat();
    }

    @Test
    void theStartingLakeIsSeventyFiveTilesOut() {
        for (long seed : new long[]{1, 123, 340}) {
            assertEquals(new MapSettings.Point(74, 4), new Evaluator(program, MapSettings.defaults(seed)).points("starting_lake_positions").get(0));
        }
        MapSettings.Point lake = new Evaluator(program, MapSettings.defaults(4_000_000_000L)).points("starting_lake_positions").get(0);
        assertEquals(75, Math.hypot(lake.x(), lake.y()), 1.5);
    }

    @Test
    void seedsBelow341ShareTheirNoiseButNotTheirOres() {
        float[][] g = grid(-64, -64, 128);
        for (int i = 0; i < g[0].length; i++) {
            g[0][i] *= 32;
            g[1][i] *= 32;
        }
        String[] roots = {"elevation", "entity:iron-ore:probability"};
        float[][] a = new Evaluator(program, MapSettings.defaults(123)).evaluate(roots, g[0], g[1]);
        float[][] b = new Evaluator(program, MapSettings.defaults(124)).evaluate(roots, g[0], g[1]);
        float[][] c = new Evaluator(program, MapSettings.defaults(123456)).evaluate(roots, g[0], g[1]);
        assertArrayEquals(a[0], b[0]);
        assertFalse(java.util.Arrays.equals(a[1], b[1]));
        assertFalse(java.util.Arrays.equals(a[0], c[0]));
    }

    @Test
    void everyRootIsANumberEverywhere() {
        Evaluator evaluator = new Evaluator(program, MapSettings.defaults(123));
        float[][] g = grid(-300, -300, 96);
        String[] roots = program.roots.keySet().toArray(new String[0]);
        float[][] values = evaluator.evaluate(roots, g[0], g[1]);
        for (int r = 0; r < roots.length; r++) {
            for (float v : values[r]) {
                assertFalse(Float.isNaN(v), roots[r] + " is NaN");
            }
        }
    }

    @Test
    void everyPresetsPropertiesResolve() {
        float[][] g = grid(0, 0, 32);
        for (Map<String, String> preset : List.of(
                Map.of("elevation", "elevation_lakes", "trees_forest_path_cutout", "1"),
                Map.of("elevation", "elevation_island", "moisture", "moisture_basic", "aux", "aux_basic",
                        "cliffiness", "cliffiness_basic", "cliff_elevation", "cliff_elevation_from_elevation",
                        "trees_forest_path_cutout", "1"))) {
            MapSettings d = MapSettings.defaults(1);
            MapSettings s = new MapSettings(1, Map.of(), preset, d.cliffElevation0(), d.cliffElevationInterval(),
                    d.cliffSmoothing(), d.cliffRichness(), d.startingPositions());
            String[] roots = program.roots.keySet().toArray(new String[0]);
            for (float[] values : new Evaluator(program, s).evaluate(roots, g[0], g[1])) {
                for (float v : values) {
                    assertFalse(Float.isNaN(v));
                }
            }
        }
    }

    @Test
    void renderSeed123() throws IOException {
        Terrain terrain = new Terrain(new Evaluator(program, MapSettings.defaults(123)));
        long start = System.nanoTime();
        BufferedImage image = MapPreview.render(terrain, 0, 0, 512);
        double seconds = (System.nanoTime() - start) / 1e9;
        File out = new File("build/nauvis-123-512.png");
        out.getParentFile().mkdirs();
        ImageIO.write(image, "png", out);

        Terrain.Area area = terrain.area(-256, -256, 512, 512);
        Map<String, Integer> tiles = new TreeMap<>();
        for (int y = -256; y < 256; y++) {
            for (int x = -256; x < 256; x++) {
                tiles.merge(area.tile(x, y).name(), 1, Integer::sum);
            }
        }
        Map<String, Integer> things = new TreeMap<>();
        area.entities().forEach(p -> things.merge(p.prototype().name(), 1, Integer::sum));
        List<String> lines = new ArrayList<>();
        lines.add(String.format("seed 123, 512 by 512 in %.2f s", seconds));
        tiles.forEach((k, v) -> lines.add(String.format("  %-14s %5.1f%%", k, 100.0 * v / (512 * 512))));
        things.forEach((k, v) -> lines.add(String.format("  %-22s %6d", k, v)));
        lines.add("  decoratives " + area.decoratives().size());
        Files.write(Path.of("build/nauvis-123-512.txt"), lines);
        System.out.println(String.join("\n", lines));
        assertTrue(tiles.size() > 5);
    }
}
