package com.jaguarm.nauvisterrain.noise;

/**
 * Factorio's `basis_noise` and its multioctave sums, built as the oracle measured them
 * (docs/NOISE.md, the built-ins) on our own gradients: zero on the lattice, each of a cell's four
 * corners adding its gradient's dot product with the offset, weighted by `(1 - d²)³`. A batch looks
 * each lattice corner's gradient up once rather than once per position, when the batch covers few.
 */
public final class BasisNoise {
    /** Every gradient's length. */
    static final double GRADIENT = 4.2;

    private static final int DIRECTIONS = 1024;
    private static final double[] GRADIENT_X = new double[DIRECTIONS];
    private static final double[] GRADIENT_Y = new double[DIRECTIONS];

    static {
        for (int i = 0; i < DIRECTIONS; i++) {
            double angle = 2 * Math.PI * (i + 0.5) / DIRECTIONS;
            GRADIENT_X[i] = GRADIENT * Math.cos(angle);
            GRADIENT_Y[i] = GRADIENT * Math.sin(angle);
        }
    }

    private BasisNoise() {
    }

    /** One octave at already scaled coordinates. */
    public static double sample(long seed0, long seed1, double x, double y) {
        double fx = Math.floor(x);
        double fy = Math.floor(y);
        long x0 = (long) fx;
        long y0 = (long) fy;
        double dx = x - fx;
        double dy = y - fy;
        long field = Hash.mix(seed0, seed1);
        return corner(gradient(field, x0, y0), dx, dy)
                + corner(gradient(field, x0 + 1, y0), dx - 1, dy)
                + corner(gradient(field, x0, y0 + 1), dx, dy - 1)
                + corner(gradient(field, x0 + 1, y0 + 1), dx - 1, dy - 1);
    }

    /** `basis_noise`. */
    public static double basis(long seed0, long seed1, double x, double y, double inputScale, double outputScale,
                               double offsetX, double offsetY) {
        return sample(seed0, seed1, (x + offsetX) * inputScale, (y + offsetY) * inputScale) * outputScale;
    }

    /**
     * `multioctave_noise`: octave `k` of `n`, counted from the coarsest, at `input_scale / 2^(n-1-k)`
     * and `output_scale · p^k / sqrt(Σ p^2j)`, so the sum keeps one octave's spread. The finest is
     * `basis_noise` with the same seeds; each coarser one is a field of its own, moved off the
     * finest one's lattice.
     */
    public static double multioctave(long seed0, long seed1, double x, double y, double persistence, int octaves,
                                     double inputScale, double outputScale, double offsetX, double offsetY) {
        double[] out = new double[1];
        multioctave(seed0, seed1, new float[]{(float) x}, new float[]{(float) y}, persistence, octaves, inputScale, outputScale,
                offsetX, offsetY, out);
        return out[0];
    }

    /** {@link #multioctave} over a batch, added into `out`. */
    static void multioctave(long seed0, long seed1, float[] xs, float[] ys, double persistence, int octaves, double inputScale,
                            double outputScale, double offsetX, double offsetY, double[] out) {
        double norm = 0;
        double weight = 1;
        for (int k = 0; k < octaves; k++) {
            norm += weight * weight;
            weight *= persistence;
        }
        double amplitude = outputScale / Math.sqrt(norm);
        double in = inputScale / Math.pow(2, octaves - 1);
        for (int k = 0; k < octaves; k++) {
            int coarseness = octaves - 1 - k;
            long field = coarseness == 0 ? seed1 : Hash.mix(seed1, coarseness);
            octave(seed0, field, xs, ys, in, offsetX, offsetY, 0.5 * coarseness, 0.25 * coarseness, amplitude, null, out);
            in *= 2;
            amplitude *= persistence;
        }
    }

    /**
     * `variable_persistence_multioctave_noise`: octave `k` from 1 to `n` at `input_scale / 2^k` and
     * `output_scale · 2^n · p^(n-k)`, all of one field.
     */
    public static double variablePersistence(long seed0, long seed1, double x, double y, double persistence,
                                             int octaves, double inputScale, double outputScale,
                                             double offsetX, double offsetY) {
        double[] out = new double[1];
        variablePersistence(seed0, seed1, new float[]{(float) x}, new float[]{(float) y}, new float[]{(float) persistence},
                octaves, inputScale, outputScale, offsetX, offsetY, out);
        return out[0];
    }

    /** {@link #variablePersistence} over a batch, each position's persistence its own, added into `out`. */
    static void variablePersistence(long seed0, long seed1, float[] xs, float[] ys, float[] persistence, int octaves,
                                    double inputScale, double outputScale, double offsetX, double offsetY, double[] out) {
        double[] amplitude = new double[xs.length];
        for (int i = 0; i < xs.length; i++) {
            amplitude[i] = outputScale * Math.pow(2, octaves) * Math.pow(persistence[i], octaves - 1);
        }
        double in = inputScale / 2;
        for (int k = 1; k <= octaves; k++) {
            octave(seed0, seed1, xs, ys, in, offsetX, offsetY, 0, 0, 1, amplitude, out);
            in /= 2;
            for (int i = 0; i < xs.length; i++) {
                amplitude[i] /= persistence[i];
            }
        }
    }

    /**
     * `quick_multioctave_noise`: octave `i` at `input_scale · m_in^i` and `output_scale · m_out^i`.
     * Its `seed0` moves by `octave_seed0_shift` each octave, and as in Factorio that changes the
     * field only when it carries past a multiple of 256.
     */
    public static double quickMultioctave(long seed0, long seed1, double x, double y, int octaves, double inputScale,
                                          double outputScale, double offsetX, double offsetY,
                                          double inputMultiplier, double outputMultiplier, long seed0Shift) {
        double[] out = new double[1];
        quickMultioctave(seed0, seed1, new float[]{(float) x}, new float[]{(float) y}, octaves, inputScale, outputScale,
                offsetX, offsetY, inputMultiplier, outputMultiplier, seed0Shift, out);
        return out[0];
    }

    /** {@link #quickMultioctave} over a batch, added into `out`. */
    static void quickMultioctave(long seed0, long seed1, float[] xs, float[] ys, int octaves, double inputScale,
                                 double outputScale, double offsetX, double offsetY, double inputMultiplier,
                                 double outputMultiplier, long seed0Shift, double[] out) {
        double in = inputScale;
        double amplitude = outputScale;
        for (int octave = 0; octave < octaves; octave++) {
            long carried = ((seed0 & 0xFF) + octave * seed0Shift) >> 8;
            octave((seed0 + (carried << 8)) & 0xFFFFFFFFL, seed1, xs, ys, in, offsetX, offsetY, 0, 0, amplitude, null, out);
            in *= inputMultiplier;
            amplitude *= outputMultiplier;
        }
    }

    /**
     * One octave over a batch, added into `out`: the field of the seeds at `(x + offset) · scale +
     * shift`, times `amplitude`, or times each position's own when `amplitudes` is given.
     */
    static void octave(long seed0, long seed1, float[] xs, float[] ys, double scale, double offsetX, double offsetY,
                       double shiftX, double shiftY, double amplitude, double[] amplitudes, double[] out) {
        int n = xs.length;
        long field = Hash.mix(seed0, seed1);
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double x = (xs[i] + offsetX) * scale + shiftX;
            double y = (ys[i] + offsetY) * scale + shiftY;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        long lx0 = (long) Math.floor(minX);
        long ly0 = (long) Math.floor(minY);
        long width = (long) Math.floor(maxX) - lx0 + 2;
        long height = (long) Math.floor(maxY) - ly0 + 2;
        int[] table = null;
        if (width * height <= 4L * n + 16) {
            table = new int[(int) (width * height)];
            for (int gy = 0; gy < height; gy++) {
                for (int gx = 0; gx < width; gx++) {
                    table[gy * (int) width + gx] = gradient(field, lx0 + gx, ly0 + gy);
                }
            }
        }
        for (int i = 0; i < n; i++) {
            double x = (xs[i] + offsetX) * scale + shiftX;
            double y = (ys[i] + offsetY) * scale + shiftY;
            double fx = Math.floor(x);
            double fy = Math.floor(y);
            double dx = x - fx;
            double dy = y - fy;
            long cx = (long) fx;
            long cy = (long) fy;
            int g00, g10, g01, g11;
            if (table != null) {
                int at = (int) ((cy - ly0) * width + (cx - lx0));
                g00 = table[at];
                g10 = table[at + 1];
                g01 = table[at + (int) width];
                g11 = table[at + (int) width + 1];
            } else {
                g00 = gradient(field, cx, cy);
                g10 = gradient(field, cx + 1, cy);
                g01 = gradient(field, cx, cy + 1);
                g11 = gradient(field, cx + 1, cy + 1);
            }
            double value = corner(g00, dx, dy) + corner(g10, dx - 1, dy) + corner(g01, dx, dy - 1) + corner(g11, dx - 1, dy - 1);
            out[i] += value * (amplitudes == null ? amplitude : amplitudes[i]);
        }
    }

    private static int gradient(long field, long x, long y) {
        return (int) (Hash.mix(field, x * 0x632BE59BD9B4E019L + y) >>> 54);
    }

    private static double corner(int g, double dx, double dy) {
        double falloff = 1 - dx * dx - dy * dy;
        if (falloff <= 0) {
            return 0;
        }
        return falloff * falloff * falloff * (GRADIENT_X[g] * dx + GRADIENT_Y[g] * dy);
    }
}
