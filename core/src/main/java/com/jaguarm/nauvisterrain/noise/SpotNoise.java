package com.jaguarm.nauvisterrain.noise;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factorio's `spot_noise`, as factorio.exe computes it (docs/NOISE.md, the built-ins): each square
 * region draws its candidate points, the most favourable become cones until the region's quantity
 * is placed, and a position takes the highest cone within reach of it, or the basement.
 */
final class SpotNoise {
    private static final int CACHE_LIMIT = 4096;
    private static final float SHRINK = Float.intBitsToFloat(0x3F700000);
    private static final float THIRD = Float.intBitsToFloat(0x3EAAAAAB);
    private static final double PI_ISH = Double.longBitsToDouble(0x400921FF2E48E8A7L);

    /** One `spot_noise` node: the nodes of its four expressions and its constants, as the engine converts them. */
    record Call(int node, int density, int quantity, int radius, int favorability, long seed0, long seed1, float basement,
                float maximumRadius, long regionSize, long skipOffset, long skipSpan, boolean hardTarget,
                long candidatePoints, float spacing) {
    }

    private record Spot(float x, float y, float peak, float slope) {
    }

    private record Key(int node, int regionX, int regionY) {
    }

    private final Evaluator evaluator;
    private final Map<Key, Spot[]> regions = new ConcurrentHashMap<>();

    SpotNoise(Evaluator evaluator) {
        this.evaluator = evaluator;
    }

    /** SpotNoise::run over one batch: the regions its bounds reach, widened by the basement radius. */
    void evaluate(Call call, float[] xs, float[] ys, float[] out) {
        Arrays.fill(out, call.basement);
        if (xs.length == 0) {
            return;
        }
        float reach = call.maximumRadius;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < xs.length; i++) {
            if (minX > xs[i]) minX = xs[i];
            if (minY > ys[i]) minY = ys[i];
            if (xs[i] > maxX) maxX = xs[i];
            if (ys[i] > maxY) maxY = ys[i];
        }
        minX -= reach;
        minY -= reach;
        maxX += reach;
        maxY += reach;
        int rx1 = region(maxX, call, true);
        int ry1 = region(maxY, call, true);
        for (int ry = region(minY, call, false); ry < ry1; ry++) {
            for (int rx = region(minX, call, false); rx < rx1; rx++) {
                for (Spot spot : spots(call, rx, ry)) {
                    if (minX > spot.x || spot.x > maxX || minY > spot.y || spot.y > maxY) {
                        continue;
                    }
                    for (int i = 0; i < xs.length; i++) {
                        float dx = xs[i] - spot.x;
                        float dy = ys[i] - spot.y;
                        float d = (float) Math.sqrt(dy * dy + dx * dx);
                        if (reach >= d) {
                            float v = spot.peak - d * spot.slope;
                            out[i] = out[i] > v ? out[i] : v;
                        }
                    }
                }
            }
        }
    }

    /** The region a coordinate falls in, through the 24.8 fixed point of a map position. */
    private static int region(float v, Call call, boolean up) {
        double scaled = v * 256.0;
        if (scaled != scaled || Double.isInfinite(scaled)) {
            throw new IllegalArgumentException("a spot_noise position is not finite");
        }
        int fixed = (int) Math.max(Math.min(scaled, 2147483647.0), -2147483648.0);
        double r = (fixed * 0x1p-8 + (call.regionSize >>> 1)) / call.regionSize;
        return truncate(up ? Math.ceil(r) : Math.floor(r));
    }

    private Spot[] spots(Call call, int rx, int ry) {
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

    /** generateSpotList and placeSpots: the picked candidates, most favourable first, until the target is placed. */
    private Spot[] place(Call call, int rx, int ry) {
        if (call.skipSpan == 0) {
            throw new IllegalArgumentException("spot_noise's skip_span is 0");
        }
        float[][] candidates = candidates(call, rx, ry);
        int n = 0;
        for (long c = call.skipOffset; c < call.candidatePoints; c += call.skipSpan) {
            n++;
        }
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int k = 0; k < n; k++) {
            int c = (int) (call.skipOffset + k * call.skipSpan);
            xs[k] = candidates[0][c];
            ys[k] = candidates[1][c];
        }
        float[][] v = evaluator.evaluateNodes(new int[]{call.density, call.quantity, call.radius, call.favorability}, xs, ys,
                null);
        float total = 0;
        for (int k = 0; k < n; k++) {
            total = total + v[0][k];
        }
        float target = total / (float) n * (float) (call.regionSize * call.regionSize & 0xFFFFFFFFL);
        List<Spot> spots = new ArrayList<>();
        float placed = 0;
        for (int k : order(v[3])) {
            if (!(target > placed)) {
                break;
            }
            float q = v[1][k];
            float r = call.maximumRadius < v[2][k] ? call.maximumRadius : v[2][k];
            if (0 >= q || 0 >= r) {
                continue;
            }
            if (call.hardTarget) {
                float wanted = q;
                float left = target - placed;
                q = q < left ? q : left;
                r = r * FastApprox.exp2f(FastApprox.log2f(q / wanted) * THIRD);
            }
            float peak = (float) (q * 3.0 / (r * PI_ISH * r));
            spots.add(new Spot(xs[k], ys[k], peak, peak / r));
            placed = placed + q;
        }
        return spots.toArray(new Spot[0]);
    }

    /** sortSpotCandidates: a stable sort, most favourable first. */
    private static int[] order(float[] favorability) {
        int n = favorability.length;
        Integer[] boxed = new Integer[n];
        for (int k = 0; k < n; k++) {
            boxed[k] = k;
        }
        boolean nan = false;
        for (float f : favorability) {
            nan |= f != f;
        }
        if (nan) {
            // The insertion sort std::stable_sort runs on up to 32; past that its merge order is not reproduced.
            for (int i = 1; i < n; i++) {
                Integer k = boxed[i];
                int j = i;
                while (j > 0 && favorability[k] > favorability[boxed[j - 1]]) {
                    boxed[j] = boxed[j - 1];
                    j--;
                }
                boxed[j] = k;
            }
        } else {
            Arrays.sort(boxed, (a, b) -> favorability[a] > favorability[b] ? -1 : favorability[b] > favorability[a] ? 1 : 0);
        }
        int[] order = new int[n];
        for (int k = 0; k < n; k++) {
            order[k] = boxed[k];
        }
        return order;
    }

    /**
     * generatePoints: integer points drawn across the region, each drawn again while it is within
     * the spacing of an earlier one, the squared spacing shrinking by a sixteenth each time.
     */
    private static float[][] candidates(Call call, int rx, int ry) {
        int size = (int) call.regionSize;
        int half = size >>> 1;
        int x0Fixed = (rx * size - half) << 8;
        int y0Fixed = (ry * size - half) << 8;
        int x0 = x0Fixed >> 8;
        int y0 = y0Fixed >> 8;
        long width = (((rx + 1) * size - half << 8) - x0Fixed >> 8) & 0xFFFFFFFFL;
        long height = (((ry + 1) * size - half << 8) - y0Fixed >> 8) & 0xFFFFFFFFL;
        long seed = ((rx * 0x1EEF + 0x3FBE2C + (int) call.seed1 * 0x1EF7 + ry * 0x1EE3) & 0xFFFFFFFFL) ^ call.seed0;
        RandomGenerator random = new RandomGenerator(seed);
        int n = (int) call.candidatePoints;
        float[] xs = new float[n];
        float[] ys = new float[n];
        float spacing2 = call.spacing * call.spacing;
        for (int i = 0; i < n; ) {
            float x = (int) (random.below(width) + x0);
            float y = (int) (random.below(height) + y0);
            boolean clear = true;
            for (int j = 0; j < i && clear; j++) {
                float dx = x - xs[j];
                float dy = y - ys[j];
                clear = !(spacing2 > dy * dy + dx * dx);
            }
            if (!clear) {
                spacing2 = spacing2 * SHRINK;
                continue;
            }
            xs[i] = x;
            ys[i] = y;
            i++;
        }
        return new float[][]{xs, ys};
    }

    /** cvttsd2si into 32 bits: out of range and NaN are the lowest integer. */
    private static int truncate(double v) {
        return v >= -2147483648.0 && v < 2147483648.0 ? (int) v : Integer.MIN_VALUE;
    }
}
