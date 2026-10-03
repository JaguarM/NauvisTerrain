package com.jaguarm.nauvisterrain.noise;

/**
 * Factorio's `basis_noise` and its multioctave sums, built as the oracle measured them
 * (docs/NOISE.md, the built-ins) on our own gradients: zero on the lattice, each of a cell's four
 * corners adding its gradient's dot product with the offset, weighted by `(1 - d²)³`.
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
        return corner(field, x0, y0, dx, dy)
                + corner(field, x0 + 1, y0, dx - 1, dy)
                + corner(field, x0, y0 + 1, dx, dy - 1)
                + corner(field, x0 + 1, y0 + 1, dx - 1, dy - 1);
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
        double norm = 0;
        double weight = 1;
        for (int k = 0; k < octaves; k++) {
            norm += weight * weight;
            weight *= persistence;
        }
        double amplitude = outputScale / Math.sqrt(norm);
        double sum = 0;
        double in = inputScale / Math.pow(2, octaves - 1);
        for (int k = 0; k < octaves; k++) {
            int coarseness = octaves - 1 - k;
            double sx = (x + offsetX) * in;
            double sy = (y + offsetY) * in;
            sum += coarseness == 0
                    ? sample(seed0, seed1, sx, sy) * amplitude
                    : sample(seed0, Hash.mix(seed1, coarseness), sx + 0.5 * coarseness, sy + 0.25 * coarseness) * amplitude;
            in *= 2;
            amplitude *= persistence;
        }
        return sum;
    }

    /**
     * `variable_persistence_multioctave_noise`: octave `k` from 1 to `n` at `input_scale / 2^k` and
     * `output_scale · 2^n · p^(n-k)`, all of one field.
     */
    public static double variablePersistence(long seed0, long seed1, double x, double y, double persistence,
                                             int octaves, double inputScale, double outputScale,
                                             double offsetX, double offsetY) {
        double sum = 0;
        double in = inputScale / 2;
        double amplitude = outputScale * Math.pow(2, octaves) * Math.pow(persistence, octaves - 1);
        for (int k = 1; k <= octaves; k++) {
            sum += sample(seed0, seed1, (x + offsetX) * in, (y + offsetY) * in) * amplitude;
            in /= 2;
            amplitude /= persistence;
        }
        return sum;
    }

    /**
     * `quick_multioctave_noise`: octave `i` at `input_scale · m_in^i` and `output_scale · m_out^i`.
     * Its `seed0` moves by `octave_seed0_shift` each octave, and as in Factorio that changes the
     * field only when it carries past a multiple of 256.
     */
    public static double quickMultioctave(long seed0, long seed1, double x, double y, int octaves, double inputScale,
                                          double outputScale, double offsetX, double offsetY,
                                          double inputMultiplier, double outputMultiplier, long seed0Shift) {
        double sum = 0;
        double in = inputScale;
        double out = outputScale;
        for (int octave = 0; octave < octaves; octave++) {
            long carried = ((seed0 & 0xFF) + octave * seed0Shift) >> 8;
            sum += sample((seed0 + (carried << 8)) & 0xFFFFFFFFL, seed1, (x + offsetX) * in, (y + offsetY) * in) * out;
            in *= inputMultiplier;
            out *= outputMultiplier;
        }
        return sum;
    }

    private static double corner(long field, long x, long y, double dx, double dy) {
        double falloff = 1 - dx * dx - dy * dy;
        if (falloff <= 0) {
            return 0;
        }
        int g = (int) (Hash.mix(field, x * 0x632BE59BD9B4E019L + y) >>> 54);
        return falloff * falloff * falloff * (GRADIENT_X[g] * dx + GRADIENT_Y[g] * dy);
    }
}
