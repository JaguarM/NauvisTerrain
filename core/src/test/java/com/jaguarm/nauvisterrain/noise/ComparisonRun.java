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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Our side of tools/compare.py: the roots a request names over its positions as one batch, for
 * each of its seeds, and the tiles over its area if it names one. Runs only when
 * build/compare/request.json is there.
 */
class ComparisonRun {
    @Test
    void run() throws IOException {
        Path request = Path.of("build/compare/request.json");
        Assumptions.assumeTrue(Files.exists(request), "no comparison request");
        JsonObject r = JsonParser.parseString(Files.readString(request)).getAsJsonObject();
        NoiseProgram program;
        try (Reader reader = Files.newBufferedReader(Path.of(r.get("program").getAsString()))) {
            program = NoiseProgram.load(reader);
        }
        String[] roots = new Gson().fromJson(r.get("roots"), String[].class);
        JsonArray points = r.getAsJsonArray("points");
        float[] xs = new float[points.size()];
        float[] ys = new float[points.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = points.get(i).getAsJsonArray().get(0).getAsFloat();
            ys[i] = points.get(i).getAsJsonArray().get(1).getAsFloat();
        }
        for (JsonElement seedElement : r.getAsJsonArray("seeds")) {
            long seed = seedElement.getAsLong();
            Evaluator evaluator = new Evaluator(program, MapSettings.defaults(seed));
            Map<String, Object> out = new LinkedHashMap<>();
            float[][] values = evaluator.evaluate(roots, xs, ys);
            for (int k = 0; k < roots.length; k++) {
                out.put(roots[k], values[k]);
            }
            if (r.has("tiles")) {
                JsonArray a = r.getAsJsonArray("tiles");
                int x0 = a.get(0).getAsInt(), y0 = a.get(1).getAsInt(), w = a.get(2).getAsInt(), h = a.get(3).getAsInt();
                Terrain.Area area = new Terrain(evaluator).area(x0, y0, w, h);
                List<String> tiles = new ArrayList<>();
                for (int y = y0; y < y0 + h; y++) {
                    for (int x = x0; x < x0 + w; x++) {
                        tiles.add(area.tile(x, y).name());
                    }
                }
                out.put("tiles", tiles);
                List<List<Object>> entities = new ArrayList<>();
                for (Terrain.Placed p : area.entities()) {
                    entities.add(List.of(p.prototype().name(), p.x(), p.y(),
                            p.prototype().type().equals("resource") ? (long) p.richness() : 0));
                }
                out.put("entities", entities);
                List<List<Object>> decoratives = new ArrayList<>();
                for (Terrain.Placed p : area.decoratives()) {
                    decoratives.add(List.of(p.prototype().name(), p.x(), p.y(), (int) p.richness()));
                }
                out.put("decoratives", decoratives);
            }
            Files.writeString(Path.of("build/compare/ours-" + seed + ".json"),
                    new GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(out));
        }
    }
}
