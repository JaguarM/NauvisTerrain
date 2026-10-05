package com.jaguarm.nauvisterrain.world;

import com.jaguarm.nauvisterrain.noise.MapSettings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What Factorio's map generator screen sets for a world: each slider's value by its key
 * (`control:water:size`, `cliff_frequency`), the properties a preset swaps, and the preset's cliff
 * smoothing. A slider that is not set is 1, a bias 0.
 */
public record NauvisMap(Map<String, Double> sliders, Map<String, String> properties, double cliffSmoothing) {
    public static final NauvisMap NORMAL = new NauvisMap(Map.of(), Map.of(), 0);

    /** Factorio's cliff interval at a frequency of 1, and its first cliff level. */
    private static final double CLIFF_INTERVAL = 40;
    private static final double CLIFF_ELEVATION_0 = 10;

    public NauvisMap {
        sliders = Map.copyOf(sliders);
        properties = Map.copyOf(properties);
    }

    public double slider(String key) {
        return sliders.getOrDefault(key, key.endsWith(":bias") ? 0.0 : 1.0);
    }

    /** The noise's map settings for a seed: the cliff interval is 40 over the cliff frequency, continuity its richness. */
    public MapSettings settings(long seed) {
        Map<String, Double> controls = new HashMap<>();
        sliders.forEach((key, value) -> {
            if (key.startsWith("control:")) {
                controls.put(key, value);
            }
        });
        return new MapSettings(seed, controls, properties, CLIFF_ELEVATION_0, CLIFF_INTERVAL / slider("cliff_frequency"),
                cliffSmoothing, slider("cliff_continuity"), List.of(new MapSettings.Point(0, 0)));
    }
}
