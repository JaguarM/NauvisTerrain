package com.jaguarm.nauvisterrain.noise;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

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
    void theNoisePackageKnowsNoMinecraft() throws IOException {
        Path dir = Path.of("src/main/java/com/jaguarm/nauvisterrain/noise");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                assertFalse(Files.readString(f).contains("net.minecraft"), f + " imports Minecraft (CLAUDE.md, rule 6)");
            }
        }
    }

    @Test
    void basisNoiseIsZeroOnTheLattice() {
        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                assertEquals(0, BasisNoise.basis(123, 7, x * 8, y * 8, 1 / 8.0, 1, 0, 0), 1e-12);
            }
        }
        assertTrue(Math.abs(BasisNoise.basis(123, 7, 4, 4, 1 / 8.0, 1, 0, 0)) > 0);
    }

    @Test
    void oneSeedPairIsOneField() {
        double a = BasisNoise.basis(5, 9, 10.3, -7.1, 1 / 16.0, 1, 0, 0);
        assertEquals(a, BasisNoise.basis(5, 9, 10.3, -7.1, 1 / 16.0, 1, 0, 0));
        assertTrue(a != BasisNoise.basis(5, 10, 10.3, -7.1, 1 / 16.0, 1, 0, 0));
        assertTrue(a != BasisNoise.basis(6, 9, 10.3, -7.1, 1 / 16.0, 1, 0, 0));
    }

    @Test
    void aSeedMakesOneMapAndAnotherSeedAnother() {
        float[][] g = grid(-64, -64, 64);
        float[] a = new Evaluator(program, MapSettings.defaults(123)).evaluate("elevation", g[0], g[1]);
        float[] b = new Evaluator(program, MapSettings.defaults(123)).evaluate("elevation", g[0], g[1]);
        float[] c = new Evaluator(program, MapSettings.defaults(124)).evaluate("elevation", g[0], g[1]);
        assertArrayEquals(a, b);
        assertFalse(java.util.Arrays.equals(a, c));
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
        area.entities.forEach(p -> things.merge(p.prototype().name(), 1, Integer::sum));
        List<String> lines = new ArrayList<>();
        lines.add(String.format("seed 123, 512 by 512 in %.2f s", seconds));
        tiles.forEach((k, v) -> lines.add(String.format("  %-14s %5.1f%%", k, 100.0 * v / (512 * 512))));
        things.forEach((k, v) -> lines.add(String.format("  %-22s %6d", k, v)));
        lines.add("  decoratives " + area.decoratives.size());
        Files.write(Path.of("build/nauvis-123-512.txt"), lines);
        System.out.println(String.join("\n", lines));
        assertTrue(tiles.size() > 5);
    }
}
