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
    void basisNoiseHasFactoriosSpread() {
        double sum = 0, sum2 = 0, sum4 = 0, max = 0;
        int n = 0;
        for (int j = 0; j < 300; j++) {
            for (int i = 0; i < 300; i++) {
                double v = BasisNoise.basis(77, 5, i + 0.37, j + 0.71, 1 / 16.0, 1, 0, 0);
                sum += v;
                sum2 += v * v;
                sum4 += v * v * v * v;
                max = Math.max(max, Math.abs(v));
                n++;
            }
        }
        double variance = sum2 / n - (sum / n) * (sum / n);
        // The oracle's numbers for Factorio's own: a spread of 0.70, kurtosis 2.3, peaks near 1.75.
        assertEquals(0.70, Math.sqrt(variance), 0.05);
        assertEquals(2.3, sum4 / n / (variance * variance), 0.15);
        assertTrue(max < 1.9 && max > 1.5, "peak " + max);
    }

    @Test
    void multioctaveSumsKeepFactoriosShapes() {
        double x = 13.75, y = -41.25;
        // Variable persistence: octave k of n at input_scale / 2^k, weighted 2^n · p^(n-k), one field.
        assertEquals(2.8 * BasisNoise.basis(9, 3, x, y, 1 / 32.0, 1, 0, 0) + 4 * BasisNoise.basis(9, 3, x, y, 1 / 64.0, 1, 0, 0),
                BasisNoise.variablePersistence(9, 3, x, y, 0.7, 2, 1 / 16.0, 1, 0, 0), 1e-6);
        // Quick: octave i at input_scale · m_in^i and output_scale · m_out^i, one field while seed0 does not carry.
        assertEquals(BasisNoise.basis(9, 3, x, y, 1 / 16.0, 1, 0, 0) + 2 * BasisNoise.basis(9, 3, x, y, 1 / 32.0, 1, 0, 0),
                BasisNoise.quickMultioctave(9, 3, x, y, 2, 1 / 16.0, 1, 0, 0, 0.5, 2, 1), 1e-9);
        // Regular: as spread out as one octave, whatever the octaves and persistence.
        double sum2 = 0;
        int n = 0;
        for (int j = 0; j < 200; j++) {
            for (int i = 0; i < 200; i++) {
                double v = BasisNoise.multioctave(11, 2, i * 3.1, j * 3.1, 0.7, 4, 1 / 8.0, 1, 0, 0);
                sum2 += v * v;
                n++;
            }
        }
        assertEquals(0.70, Math.sqrt(sum2 / n), 0.07);
    }

    @Test
    void theStartingLakeIsATileSeventyFiveTilesOut() {
        for (long seed : new long[]{1, 123, 4_000_000_000L}) {
            MapSettings.Point lake = new Evaluator(program, MapSettings.defaults(seed)).points("starting_lake_positions").get(0);
            assertEquals(Math.floor(lake.x()), lake.x());
            assertEquals(Math.floor(lake.y()), lake.y());
            assertEquals(75, Math.hypot(lake.x(), lake.y()), 1.5);
        }
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
