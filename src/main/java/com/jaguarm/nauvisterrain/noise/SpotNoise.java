package com.jaguarm.nauvisterrain.noise;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * `spot_noise`: conical spots chosen per square region from a series of candidate points, the
 * value at a position the highest spot there or the basement (docs/NOISE.md, the built-ins).
 */
final class SpotNoise {
    /** How often a candidate is drawn again for being nearer than the spacing to an earlier one. */
    private static final int SPACING_TRIES = 8;
    private static final int CACHE_LIMIT = 4096;

    /** The constant parameters of one `spot_noise` call, in the node's order. */
    record Call(int node, int density, int quantity, int radius, int favorability, long seed0, long seed1,
                double basement, double maximumBasementRadius, double regionSize, int skipOffset, int skipSpan,
                boolean hardTarget, int candidatePoints, double spacing) {
    }

    record Spot(double x, double y, double radius, double peak) {
    }

    private record Key(int node, long regionX, long regionY) {
    }

    private final Evaluator evaluator;
    private final Map<Key, Spot[]> regions = new ConcurrentHashMap<>();

    SpotNoise(Evaluator evaluator) {
        this.evaluator = evaluator;
    }

    void evaluate(Call call, float[] xs, float[] ys, float[] out) {
        double size = call.regionSize;
        double reach = call.maximumBasementRadius;
        for (int i = 0; i < out.length; i++) {
            double x = xs[i];
            double y = ys[i];
            double best = call.basement;
            long rx0 = (long) Math.floor((x - reach) / size);
            long rx1 = (long) Math.floor((x + reach) / size);
            long ry0 = (long) Math.floor((y - reach) / size);
            long ry1 = (long) Math.floor((y + reach) / size);
            for (long rx = rx0; rx <= rx1; rx++) {
                for (long ry = ry0; ry <= ry1; ry++) {
                    for (Spot spot : spots(call, rx, ry)) {
                        double d = Math.hypot(x - spot.x, y - spot.y);
                        if (d <= reach) {
                            best = Math.max(best, spot.peak * (1 - d / spot.radius));
                        }
                    }
                }
            }
            out[i] = (float) best;
        }
    }

    Spot[] spots(Call call, long rx, long ry) {
        Key key = new Key(call.node, rx, ry);
        Spot[] found = regions.get(key);
        if (found == null) {
            if (regions.size() > CACHE_LIMIT) {
                regions.clear();
            }
            found = place(call, rx, ry);
            regions.put(key, found);
        }
        return found;
    }

    /** The region's spots: its candidates, most favourable first, until the region's quantity is reached. */
    private Spot[] place(Call call, long rx, long ry) {
        double[][] points = candidates(call, rx, ry);
        int count = 0;
        for (int i = call.skipOffset; i < points.length; i += call.skipSpan) {
            count++;
        }
        if (count == 0) {
            return new Spot[0];
        }
        float[] xs = new float[count];
        float[] ys = new float[count];
        for (int k = 0, i = call.skipOffset; k < count; k++, i += call.skipSpan) {
            xs[k] = (float) points[i][0];
            ys[k] = (float) points[i][1];
        }
        float[][] v = evaluator.evaluateNodes(
                new int[]{call.density, call.quantity, call.radius, call.favorability}, xs, ys);
        double density = 0;
        for (float d : v[0]) {
            density += d;
        }
        double target = density / count * call.regionSize * call.regionSize;
        Integer[] order = new Integer[count];
        for (int k = 0; k < count; k++) {
            order[k] = k;
        }
        Arrays.sort(order, Comparator.comparingDouble(k -> -v[3][k]));
        List<Spot> spots = new ArrayList<>();
        double total = 0;
        for (int k : order) {
            if (total >= target) {
                break;
            }
            double quantity = v[1][k];
            double radius = v[2][k];
            if (quantity <= 0 || radius <= 0) {
                continue;
            }
            if (call.hardTarget && total + quantity > target) {
                quantity = target - total;
            }
            total += quantity;
            spots.add(new Spot(xs[k], ys[k], radius, 3 * quantity / (Math.PI * radius * radius)));
        }
        return spots.toArray(new Spot[0]);
    }

    /**
     * The region's share of the series every call with these seeds, region size and spacing draws
     * from: random points in the region, each redrawn a few times while nearer than the spacing to
     * an earlier one.
     */
    private static double[][] candidates(Call call, long rx, long ry) {
        long seed = Hash.of(Hash.mix(call.seed0, call.seed1), Double.doubleToLongBits(call.regionSize),
                Double.doubleToLongBits(call.spacing), Hash.mix(rx, ry));
        SplittableRandom random = new SplittableRandom(seed);
        double[][] points = new double[call.candidatePoints][];
        double spacing2 = call.spacing * call.spacing;
        for (int i = 0; i < points.length; i++) {
            double x = 0;
            double y = 0;
            for (int attempt = 0; attempt < SPACING_TRIES; attempt++) {
                x = (rx + random.nextDouble()) * call.regionSize;
                y = (ry + random.nextDouble()) * call.regionSize;
                if (clear(points, i, x, y, spacing2)) {
                    break;
                }
            }
            points[i] = new double[]{x, y};
        }
        return points;
    }

    private static boolean clear(double[][] points, int count, double x, double y, double spacing2) {
        for (int j = 0; j < count; j++) {
            double dx = points[j][0] - x;
            double dy = points[j][1] - y;
            if (dx * dx + dy * dy < spacing2) {
                return false;
            }
        }
        return true;
    }
}
