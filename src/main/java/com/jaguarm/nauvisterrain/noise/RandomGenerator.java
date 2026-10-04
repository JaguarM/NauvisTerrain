package com.jaguarm.nauvisterrain.noise;

/** Factorio's random generator: L'Ecuyer's taus88, three words that all start at the seed, or at 341 below it. */
final class RandomGenerator {
    private int s1;
    private int s2;
    private int s3;

    RandomGenerator(long seed) {
        int s = (int) Math.max(seed & 0xFFFFFFFFL, 341);
        s1 = s;
        s2 = s;
        s3 = s;
    }

    /** The next 32 bits, unsigned. */
    long next() {
        s1 = ((s1 & 0xFFFFFFFE) << 12) ^ (((s1 << 13) ^ s1) >>> 19);
        s2 = ((s2 & 0xFFFFFFF8) << 4) ^ (((s2 << 2) ^ s2) >>> 25);
        s3 = ((s3 & 0xFFFFFFF0) << 17) ^ (((s3 << 3) ^ s3) >>> 11);
        return (s1 ^ s2 ^ s3) & 0xFFFFFFFFL;
    }

    /** A number below `n`, an unsigned 32-bit count; nothing is drawn when `n` is 1 or less. */
    long below(long n) {
        return n <= 1 ? 0 : next() % n;
    }

    /** Factorio's randomShuffle: Fisher-Yates from the top. */
    void shuffle(int[] a) {
        for (int n = a.length; n > 1; n--) {
            int j = (int) below(n);
            int t = a[n - 1];
            a[n - 1] = a[j];
            a[j] = t;
        }
    }
}
