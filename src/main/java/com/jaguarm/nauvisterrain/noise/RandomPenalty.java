package com.jaguarm.nauvisterrain.noise;

/**
 * Factorio's `random_penalty` over a batch: one random generator, seeded from the batch's first
 * position, and one draw for each position whose source is above 0, from the last position to the first.
 */
final class RandomPenalty {
    private RandomPenalty() {
    }

    static void apply(float[] xs, float[] ys, float[] source, long seed, float amplitude, float[] out) {
        if (xs.length == 0) {
            return;
        }
        RandomGenerator random = new RandomGenerator((long) xs[0] * 0x1EEF + 0x3FBE2C + (long) ((float) seed + ys[0]) * 0x1EE3);
        for (int i = xs.length - 1; i >= 0; i--) {
            float v = source[i];
            out[i] = 0 >= v ? v : (float) (v - random.next() * 0x1p-32 * amplitude);
        }
    }
}
