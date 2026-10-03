package com.jaguarm.nauvisterrain.noise;

/**
 * Gradient noise standing in for Factorio's `basis_noise`, and the multioctave sums built from it
 * (docs/NOISE.md, the built-ins). Zero wherever the scaled coordinates are integers.
 */
public final class BasisNoise {
    /** Scales the raw gradient noise, whose peak is about 0.7, to Factorio's range. */
    static final double AMPLITUDE = Math.sqrt(2);

    private static final int GRADIENTS = 256;
    private static final double[] GRADIENT_X = new double[GRADIENTS];
    private static final double[] GRADIENT_Y = new double[GRADIENTS];

    static {
        for (int i = 0; i < GRADIENTS; i++) {
            double angle = 2 * Math.PI * i / GRADIENTS;
            GRADIENT_X[i] = Math.cos(angle);
            GRADIENT_Y[i] = Math.sin(angle);
        }
    }

    private BasisNoise() {
    }

    /** One octave at already scaled coordinates. */
    public static double sample(long seed0, long seed1, double x, double y) {
        double fx0 = Math.floor(x);
        double fy0 = Math.floor(y);
        long x0 = (long) fx0;
        long y0 = (long) fy0;
        double dx = x - fx0;
        double dy = y - fy0;
        double n00 = corner(seed0, seed1, x0, y0, dx, dy);
        double n10 = corner(seed0, seed1, x0 + 1, y0, dx - 1, dy);
        double n01 = corner(seed0, seed1, x0, y0 + 1, dx, dy - 1);
        double n11 = corner(seed0, seed1, x0 + 1, y0 + 1, dx - 1, dy - 1);
        double u = fade(dx);
        double v = fade(dy);
        double top = n00 + u * (n10 - n00);
        double bottom = n01 + u * (n11 - n01);
        return (top + v * (bottom - top)) * AMPLITUDE;
    }

    /** `basis_noise`. */
    public static double basis(long seed0, long seed1, double x, double y, double inputScale, double outputScale,
                               double offsetX, double offsetY) {
        return sample(seed0, seed1, (x + offsetX) * inputScale, (y + offsetY) * inputScale) * outputScale;
    }

    /**
     * `multioctave_noise` and its variable-persistence twin. `input_scale` is the finest octave's;
     * each coarser one has half the input scale and twice the output scale, and every octave but
     * the coarsest is `persistence` times the next coarser in amplitude.
     */
    public static double multioctave(long seed0, long seed1, double x, double y, double persistence, int octaves,
                                     double inputScale, double outputScale, double offsetX, double offsetY) {
        double sum = 0;
        double in = inputScale / (1L << (octaves - 1));
        double out = outputScale * (1L << (octaves - 1));
        for (int octave = 0; octave < octaves; octave++) {
            sum += sample(seed0, Hash.mix(seed1, octave), (x + offsetX) * in, (y + offsetY) * in) * out;
            in *= 2;
            out *= persistence;
        }
        return sum;
    }

    /**
     * `quick_multioctave_noise`: octave `i` at `input_scale · m_in^i` and `output_scale · m_out^i`,
     * with `seed0` moved by `i · octave_seed0_shift`.
     */
    public static double quickMultioctave(long seed0, long seed1, double x, double y, int octaves, double inputScale,
                                          double outputScale, double offsetX, double offsetY,
                                          double inputMultiplier, double outputMultiplier, long seed0Shift) {
        double sum = 0;
        double in = inputScale;
        double out = outputScale;
        for (int octave = 0; octave < octaves; octave++) {
            sum += sample((seed0 + octave * seed0Shift) & 0xFFFFFFFFL, seed1, (x + offsetX) * in, (y + offsetY) * in) * out;
            in *= inputMultiplier;
            out *= outputMultiplier;
        }
        return sum;
    }

    private static double corner(long seed0, long seed1, long x, long y, double dx, double dy) {
        int g = (int) (Hash.of(seed0, seed1, x, y) & (GRADIENTS - 1));
        return GRADIENT_X[g] * dx + GRADIENT_Y[g] * dy;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }
}
