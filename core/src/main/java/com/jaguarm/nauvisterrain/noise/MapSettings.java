package com.jaguarm.nauvisterrain.noise;

import java.util.List;
import java.util.Map;

/**
 * What Factorio's map generator screen sets: the seed, each control's sliders by its Factorio name
 * (`control:water:frequency`), the climate's, the cliff settings, the properties a preset swaps,
 * and the starting positions. A slider that is not given is 1, a bias 0.
 */
public record MapSettings(long seed, Map<String, Double> controls, Map<String, String> properties,
                          double cliffElevation0, double cliffElevationInterval, double cliffSmoothing,
                          double cliffRichness, List<Point> startingPositions) {

    public record Point(double x, double y) {
    }

    public MapSettings {
        seed &= 0xFFFFFFFFL;
        controls = Map.copyOf(controls);
        properties = Map.copyOf(properties);
        startingPositions = List.copyOf(startingPositions);
    }

    /** Factorio's defaults for a seed: every slider normal, one start at 0,0. */
    public static MapSettings defaults(long seed) {
        return new MapSettings(seed, Map.of(), Map.of(), 10, 40, 0, 1, List.of(new Point(0, 0)));
    }

    public double control(String name) {
        Double value = controls.get(name);
        if (value != null) {
            return value;
        }
        return name.endsWith(":bias") ? 0 : 1;
    }

    /** The value of a property a preset swaps, or "" for the planet's own. */
    public String property(String name) {
        return properties.getOrDefault(name, "");
    }
}
