package com.jaguarm.nauvisterrain.noise;

/** Paul Mineiro's fastapprox as Factorio's engine has it: its exp2f, fastlog2, and the `pow` of expressions. */
final class FastApprox {
    private static final float EXP_MIN = Float.intBitsToFloat(0xC2FC0000);
    private static final float EXP_C0 = Float.intBitsToFloat(0x42F28C51);
    private static final float EXP_C1 = Float.intBitsToFloat(0x41DDD2FE);
    private static final float EXP_C2 = Float.intBitsToFloat(0x409AF5F8);
    private static final float EXP_C3 = Float.intBitsToFloat(0x3FBEBC8D);
    private static final float LOG_SCALE = Float.intBitsToFloat(0x34000000);
    private static final float LOG_C0 = Float.intBitsToFloat(0x42F87377);
    private static final float LOG_C1 = Float.intBitsToFloat(0x3FBFBF75);
    private static final float LOG_C2 = Float.intBitsToFloat(0x3FDCE9A3);
    private static final float LOG_C3 = Float.intBitsToFloat(0x3EB444F9);

    private FastApprox() {
    }

    /** Math::exp2f: 2 to the `p`. */
    static float exp2f(float p) {
        float offset = 0 > p ? 1 : 0;
        float clipped = EXP_MIN > p ? EXP_MIN : p;
        int whole = clipped >= 0x1p31f || clipped != clipped ? Integer.MIN_VALUE : (int) clipped;
        float z = (clipped - whole) + offset;
        float v = (EXP_C1 / (EXP_C2 - z) + (clipped + EXP_C0)) - z * EXP_C3;
        return Float.intBitsToFloat((int) (long) (v * 0x1p23f));
    }

    /** fastlog2: the logarithm to base 2. */
    static float log2f(float x) {
        int bits = Float.floatToRawIntBits(x);
        float y = (float) (bits & 0xFFFFFFFFL) * LOG_SCALE;
        float m = Float.intBitsToFloat((bits & 0x7FFFFF) | 0x3F000000);
        return ((y - LOG_C0) - m * LOG_C1) - LOG_C2 / (m + LOG_C3);
    }

    /** NoiseOperations::Functions::pow: whole powers by repeated squaring, the rest through 2^(b·log2 a). */
    static float pow(float a, float b) {
        if (a == 0) {
            return b == 0 ? 1 : 0 > b ? Float.POSITIVE_INFINITY : 0;
        }
        long n = Math.abs(b) < 0x1p31f ? (long) b : Integer.MIN_VALUE;
        if ((float) n == b) {
            float r = 1;
            float m = a;
            for (long e = Math.abs(n); e != 0; e >>= 1) {
                if ((e & 1) != 0) {
                    r = r * m;
                }
                m = m * m;
            }
            return n < 0 ? 1 / r : r;
        }
        if (a != a || 0 > a) {
            return Float.NaN;
        }
        return exp2f(log2f(a) * b);
    }
}
