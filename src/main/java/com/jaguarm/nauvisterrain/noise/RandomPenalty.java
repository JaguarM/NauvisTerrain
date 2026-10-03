package com.jaguarm.nauvisterrain.noise;

/** The random number `random_penalty` scales by its amplitude: one per position, seed and map seed. */
final class RandomPenalty {
    private RandomPenalty() {
    }

    /** A number in [0, 1). */
    static double unit(long mapSeed, long seed, float x, float y) {
        return Hash.unit(Hash.of(mapSeed, seed, Float.floatToIntBits(x), Float.floatToIntBits(y)));
    }
}
