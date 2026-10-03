package com.jaguarm.nauvisterrain.noise;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Our side of tools/calibrate.py: the roots a request names, over its grid, for each of its seeds,
 * and what is placed over its area if it names one. Runs only when build/calibration/request.json
 * is there.
 */
class CalibrationRun {
    @Test
    void run() throws IOException {
        Path request = Path.of("build/calibration/request.json");
        Assumptions.assumeTrue(Files.exists(request), "no calibration request");
        JsonObject r = JsonParser.parseString(Files.readString(request)).getAsJsonObject();
        NoiseProgram program;
        try (Reader reader = Files.newBufferedReader(Path.of(r.get("program").getAsString()))) {
            program = NoiseProgram.load(reader);
        }
        String[] roots = new Gson().fromJson(r.get("roots"), String[].class);
        JsonArray grid = r.getAsJsonArray("grid");
        double x0 = grid.get(0).getAsDouble(), y0 = grid.get(1).getAsDouble(), step = grid.get(4).getAsDouble();
        int w = grid.get(2).getAsInt(), h = grid.get(3).getAsInt();
        for (JsonElement seedElement : r.getAsJsonArray("seeds")) {
            long seed = seedElement.getAsLong();
            Evaluator evaluator = new Evaluator(program, MapSettings.defaults(seed));
            Map<String, float[]> values = new LinkedHashMap<>();
            for (String root : roots) {
                values.put(root, new float[w * h]);
            }
            int batch = 1024;
            for (int first = 0; first < w * h; first += batch) {
                int n = Math.min(batch, w * h - first);
                float[] xs = new float[n];
                float[] ys = new float[n];
                for (int k = 0; k < n; k++) {
                    int i = first + k;
                    xs[k] = (float) (x0 + (i % w) * step);
                    ys[k] = (float) (y0 + (i / w) * step);
                }
                float[][] v = evaluator.evaluate(roots, xs, ys);
                for (int k = 0; k < roots.length; k++) {
                    System.arraycopy(v[k], 0, values.get(roots[k]), first, n);
                }
            }
            Files.writeString(Path.of("build/calibration/ours-" + seed + ".json"),
                    new GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(values));
            if (r.has("area")) {
                JsonArray a = r.getAsJsonArray("area");
                Terrain.Area area = new Terrain(evaluator).area(a.get(0).getAsInt(), a.get(1).getAsInt(),
                        a.get(2).getAsInt(), a.get(3).getAsInt());
                Map<String, Integer> counts = new LinkedHashMap<>();
                area.entities().forEach(p -> counts.merge(p.prototype().name(), 1, Integer::sum));
                area.decoratives().forEach(p -> counts.merge(p.prototype().name(), 1, Integer::sum));
                Files.writeString(Path.of("build/calibration/placed-" + seed + ".json"), new Gson().toJson(counts));
            }
        }
    }
}
