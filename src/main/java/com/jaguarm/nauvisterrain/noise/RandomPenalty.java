package com.jaguarm.nauvisterrain.noise;

/** The random number `random_penalty` scales by its amplitude: one per position and seed, whatever the map seed. */
final class RandomPenalty {
    private RandomPenalty() {
    }

    /** A number in [0, 1). */
    static double unit(long seed, float x, float y) {
        return Hash.unit(Hash.of(0x5EED, seed, Float.floatToIntBits(x), Float.floatToIntBits(y)));
    }
}
