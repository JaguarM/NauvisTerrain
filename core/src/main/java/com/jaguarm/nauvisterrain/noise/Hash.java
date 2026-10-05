package com.jaguarm.nauvisterrain.noise;

/** The integer hash every random choice here is made from: SplitMix64's finaliser over the inputs. */
public final class Hash {
    private Hash() {
    }

    public static long mix(long a, long b) {
        return finish(finish(a * 0x9E3779B97F4A7C15L) ^ b);
    }

    public static long of(long a, long b, long c, long d) {
        return mix(mix(mix(a, b), c), d);
    }

    /** A hash as a double in [0, 1). */
    public static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }

    private static long finish(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
