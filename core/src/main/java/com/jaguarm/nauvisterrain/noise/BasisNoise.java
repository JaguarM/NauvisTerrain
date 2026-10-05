package com.jaguarm.nauvisterrain.noise;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factorio's `basis_noise` and the built-ins made of it, as factorio.exe computes them (docs/NOISE.md,
 * the built-ins): the seed's tables, and a batch of positions through Factorio's vector path, or
 * through its grid path when the batch is a grid and the noise reads `x` and `y` as they are.
 * Everything adds into `out`.
 */
public final class BasisNoise {
    private static final float[] DEFAULT_X = new float[256];
    private static final float[] DEFAULT_Y = new float[256];
    private static final int TABLES_KEPT = 4096;
    private static final Map<Long, Tables> TABLES = new ConcurrentHashMap<>();

    static {
        for (int i = 0; i < 256; i++) {
            double turns = (float) (i * 0.02454369260617026) * 0.15915494309189535;
            DEFAULT_X[i] = (float) (cosTurns(turns) * 4.2);
            DEFAULT_Y[i] = (float) (sinTurns(turns) * 4.2);
        }
    }

    /** The positions of a batch laid out as a chunk is: rows of x from x0, y0, each step apart. */
    public record Grid(float x0, float y0, int width, int height, float step) {
        int count() {
            return width * height;
        }
    }

    /** Noise::setSeed's tables: a byte, two permutations and the gradients in their shuffled order. */
    record Tables(int p1, int[] p2, int[] p3, float[] gx, float[] gy) {
    }

    private BasisNoise() {
    }

    /** The tables `basis_noise{seed0, seed1}` uses. */
    static Tables tables(long seed0, long seed1) {
        long s0 = (seed0 + 7 * (seed1 >>> 8)) & 0xFFFFFFFFL;
        int s1 = (int) (seed1 & 255);
        long key = s0 << 8 | s1;
        Tables t = TABLES.get(key);
        if (t == null) {
            if (TABLES.size() >= TABLES_KEPT) {
                TABLES.clear();
            }
            t = seed(s0, s1);
            TABLES.put(key, t);
        }
        return t;
    }

    private static Tables seed(long seed0, int seed1) {
        RandomGenerator random = new RandomGenerator(seed0);
        int[] first = identity();
        random.shuffle(first);
        int[] p2 = identity();
        random.shuffle(p2);
        int[] p3 = identity();
        random.shuffle(p3);
        int[] order = identity();
        random.shuffle(order);
        float[] gx = new float[256];
        float[] gy = new float[256];
        for (int i = 0; i < 256; i++) {
            gx[i] = DEFAULT_X[order[i]];
            gy[i] = DEFAULT_Y[order[i]];
        }
        return new Tables(first[seed1], p2, p3, gx, gy);
    }

    // -- the built-ins --------------------------------------------------------------------------

    /** `basis_noise`. */
    static void basis(long seed0, long seed1, float[] xs, float[] ys, Grid grid, float inputScale, float outputScale,
                      float offsetX, float offsetY, float[] out) {
        noise(tables(seed0, seed1), xs, ys, grid, inputScale, outputScale, offsetX, offsetY, out);
    }

    /** `multioctave_noise`: all octaves of one field, the finest first. */
    static void multioctave(long seed0, long seed1, float[] xs, float[] ys, Grid grid, float persistence, float octaves,
                            float inputScale, float outputScale, float offsetX, float offsetY, float[] out) {
        Tables t = tables(seed0, seed1);
        int n = (int) Math.ceil(octaves);
        float inverse = 1 / persistence;
        float scale = FastApprox.exp2f((float) n - octaves);
        if (1 > scale) {
            scale = 1;
        } else if (scale >= 1.99999) {
            scale = Float.intBitsToFloat(0x3FFFFFAC);
        }
        float amplitude = modifiedAmplitude(outputScale, n, inverse);
        if (grid != null && inputScale > 0 && 2 * ceilCount(grid, inputScale) <= grid.count()) {
            float x0 = (offsetX + grid.x0) / grid.step;
            float y0 = (offsetY + grid.y0) / grid.step;
            float step = grid.step * scale;
            for (int k = 0; k < n; k++) {
                float x = (float) (k * 17.17 / inputScale + x0 * step);
                gridNoise(t, x, y0 * step, grid.width, grid.height, step, inputScale, amplitude, out);
                step = (float) (step * 0.5);
                amplitude = amplitude * inverse;
            }
            return;
        }
        scale = scale * inputScale;
        float[] bx = new float[xs.length];
        float[] by = new float[xs.length];
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < xs.length; i++) {
                bx[i] = (float) (scale * xs[i] + k * 17.17);
                by[i] = scale * ys[i];
            }
            vectorNoise(t, bx, by, 1, amplitude, offsetX, offsetY, out);
            scale = (float) (scale * 0.5);
            amplitude = amplitude * inverse;
        }
    }

    /** `variable_persistence_multioctave_noise`: one field, each coarser octave under one more factor of persistence. */
    static void variablePersistence(long seed0, long seed1, float[] xs, float[] ys, Grid grid, float[] persistence,
                                    long octaves, float inputScale, float outputScale, float offsetX, float offsetY,
                                    float[] out) {
        Tables t = tables(seed0, seed1);
        float scale = inputScale * 0.5f;
        float weight = (float) (Math.pow(2, octaves) * outputScale);
        for (long k = 1; k < octaves; k++) {
            noise(t, xs, ys, grid, scale, 1, offsetX, offsetY, out);
            scale = scale * 0.5f;
            for (int i = 0; i < out.length; i++) {
                out[i] = persistence[i] * out[i];
            }
        }
        noise(t, xs, ys, grid, scale, 1, offsetX, offsetY, out);
        for (int i = 0; i < out.length; i++) {
            out[i] = weight * out[i];
        }
    }

    /** `quick_multioctave_noise`: one `basis_noise` per octave, its seed0 moved by the shift each time. */
    static void quickMultioctave(long seed0, long seed1, float[] xs, float[] ys, Grid grid, long octaves, float inputScale,
                                 float outputScale, float offsetX, float offsetY, float inputMultiplier,
                                 float outputMultiplier, long seed0Shift, float[] out) {
        long s0 = seed0;
        for (long k = 0; k < octaves; k++) {
            noise(tables(s0, seed1), xs, ys, grid, inputScale, outputScale, offsetX, offsetY, out);
            inputScale = inputScale * inputMultiplier;
            outputScale = outputScale * outputMultiplier;
            s0 = (s0 + seed0Shift) & 0xFFFFFFFFL;
        }
    }

    /** Noise::modifiedAmplitude: the finest of `n` octaves' amplitude, so their squares sum to the output scale's. */
    static float modifiedAmplitude(float outputScale, int n, float inverse) {
        if (inverse == 1) {
            return (float) (outputScale / Math.sqrt(n));
        }
        if (inverse == 0) {
            return outputScale;
        }
        float q = inverse * inverse;
        float r = (q - 1) / (FastApprox.exp2f(FastApprox.log2f(q) * n) - 1);
        return (float) (Math.sqrt(r) * outputScale);
    }

    // -- the two paths ----------------------------------------------------------------------------

    /** Noise::noise on registers: the grid path when it applies, else the vector path. */
    static void noise(Tables t, float[] xs, float[] ys, Grid grid, float inputScale, float outputScale, float offsetX,
                      float offsetY, float[] out) {
        if (grid != null && inputScale > 0 && ceilCount(grid, inputScale) <= grid.count()) {
            gridNoise(t, offsetX + grid.x0, offsetY + grid.y0, grid.width, grid.height, grid.step, inputScale, outputScale,
                    out);
        } else {
            vectorNoise(t, xs, ys, inputScale, outputScale, offsetX, offsetY, out);
        }
    }

    /** The gradients a grid row spans, as the scratch must hold them. */
    private static long ceilCount(Grid grid, float inputScale) {
        return (long) (float) Math.ceil((float) grid.width * grid.step * inputScale);
    }

    /** The vector path: each position on its own. */
    static void vectorNoise(Tables t, float[] xs, float[] ys, float inputScale, float outputScale, float offsetX,
                            float offsetY, float[] out) {
        int[] p2 = t.p2;
        int[] p3 = t.p3;
        float[] gx = t.gx;
        float[] gy = t.gy;
        for (int i = 0; i < xs.length; i++) {
            float x = (offsetX + xs[i]) * inputScale;
            float y = (offsetY + ys[i]) * inputScale;
            float x0 = (float) Math.floor(x);
            float x1 = (float) Math.ceil(x);
            float y0 = (float) Math.floor(y);
            float fx = x - x0;
            float fy = y - y0;
            int cx0 = p3[(int) (long) x0 & 255];
            int cx1 = p3[(int) (long) x1 & 255];
            long row = (long) y0;
            int r0 = p2[(int) row & 255] ^ t.p1;
            int r1 = p2[(int) (row + 1) & 255] ^ t.p1;
            float l0 = lane(gx, gy, cx0 ^ r0, fx, fy);
            float l1 = lane(gx, gy, cx1 ^ r0, fx - 1, fy);
            float l2 = lane(gx, gy, cx0 ^ r1, fx, fy - 1);
            float l3 = lane(gx, gy, cx1 ^ r1, fx - 1, fy - 1);
            out[i] = ((l0 + l1) + (l2 + l3)) * outputScale + out[i];
        }
    }

    private static float lane(float[] gx, float[] gy, int g, float dx, float dy) {
        float d2 = dy * dy + dx * dx;
        float t = 1 - (d2 < 1 ? d2 : 1);
        return (dy * gy[g] + dx * gx[g]) * (t * t * t);
    }

    /** The grid path: a row's gradients are looked up once, and its lattice cells' once per row. */
    static void gridNoise(Tables t, float x0, float y0, int width, int height, float step, float inputScale,
                          float outputScale, float[] out) {
        float sx0 = x0 * inputScale;
        float fx0 = (float) Math.floor(sx0);
        int count = (int) (long) ((float) Math.ceil((float) (width - 1) * step * inputScale + sx0) - fx0) + 1;
        long xStart = (long) fx0;
        long yStart = (long) (float) Math.floor(y0 * inputScale);
        int[] cell = new int[width];
        float[] fx = new float[width];
        for (int i = 0; i < width; i++) {
            float x = ((float) i * step + x0) * inputScale;
            float floor = (float) Math.floor(x);
            cell[i] = (int) (long) floor - (int) xStart;
            fx[i] = x - floor;
        }
        float[] top = new float[2 * count + 2];
        float[] bottom = new float[2 * count + 2];
        gradientsLine(t, count, xStart, yStart, bottom);
        int lastRow = -1;
        for (int j = 0; j < height; j++) {
            float y = ((float) j * step + y0) * inputScale;
            float floor = (float) Math.floor(y);
            float fy = y - floor;
            int row = (int) (long) floor - (int) yStart;
            if (row == lastRow + 1) {
                float[] reused = top;
                top = bottom;
                bottom = reused;
                gradientsLine(t, count, xStart, yStart + 1 + row, bottom);
                lastRow = row;
            } else if (row != lastRow) {
                // Factorio's grid path leaves its remembered row as it was here.
                gradientsLine(t, count, xStart, yStart + row, top);
                gradientsLine(t, count, xStart, yStart + row + 1, bottom);
            }
            float dy1 = fy - 1;
            float above = 1 - fy * fy;
            float below = 1 - dy1 * dy1;
            int lastCell = -1;
            float gx0 = 0, gx1 = 0, gx2 = 0, gx3 = 0, ydot0 = 0, ydot1 = 0, ydot2 = 0, ydot3 = 0;
            int at = j * width;
            for (int i = 0; i < width; i++) {
                int c = cell[i];
                if (c != lastCell) {
                    int g = 2 * c;
                    gx0 = top[g];
                    gx1 = top[g + 2];
                    gx2 = bottom[g];
                    gx3 = bottom[g + 2];
                    ydot0 = top[g + 1] * fy;
                    ydot1 = top[g + 3] * fy;
                    ydot2 = bottom[g + 1] * dy1;
                    ydot3 = bottom[g + 3] * dy1;
                    lastCell = c;
                }
                float dx0 = fx[i];
                float dx1 = fx[i] - 1;
                float l0 = gridLane(above, dx0, gx0, ydot0);
                float l1 = gridLane(above, dx1, gx1, ydot1);
                float l2 = gridLane(below, dx0, gx2, ydot2);
                float l3 = gridLane(below, dx1, gx3, ydot3);
                out[at + i] = ((l0 + l2) + (l1 + l3)) * outputScale + out[at + i];
            }
        }
    }

    private static float gridLane(float oneLessDy2, float dx, float gx, float ydot) {
        float t = oneLessDy2 - dx * dx;
        t = t > 0 ? t : 0;
        return (dx * gx + ydot) * (t * t * t);
    }

    /** Noise::gradientsLine: the gradients of `count` lattice points from `xStart` along row `y`, x and y paired. */
    private static void gradientsLine(Tables t, int count, long xStart, long y, float[] into) {
        int row = t.p2[(int) y & 255] ^ t.p1;
        for (int k = 0; k < count; k++) {
            int g = t.p3[(int) (xStart + k) & 255] ^ row;
            into[2 * k] = t.gx[g];
            into[2 * k + 1] = t.gy[g];
        }
    }

    /** The cosine of an angle in turns, through the engine's polynomial. */
    static double cosTurns(double turns) {
        return turnSine(0.25 - Math.abs(turns - Math.rint(turns)));
    }

    /** The sine of an angle in turns, through the engine's polynomial. */
    static double sinTurns(double turns) {
        double t = turns - 0.25;
        return turnSine(0.25 - Math.abs(t - Math.rint(t)));
    }

    /** The engine's polynomial for sin(2π·w), w within a quarter turn of 0. */
    private static double turnSine(double w) {
        double s = w * w;
        double s2 = s * s;
        double r = (81.60201529595571 - s * 76.56887678023256) * s2 + (6.283185269630412 - s * 41.34167506665737);
        r = r + s2 * s2 * 39.65735524898863;
        return r * w;
    }

    private static int[] identity() {
        int[] a = new int[256];
        for (int i = 0; i < 256; i++) {
            a[i] = i;
        }
        return a;
    }
}
