package com.jaguarm.nauvisterrain.world;

import com.jaguarm.nauvisterrain.noise.NoiseProgram;

/** Nauvis's noise program, read from the jar once. */
public final class Nauvis {
    private static volatile NoiseProgram program;

    private Nauvis() {
    }

    public static NoiseProgram program() {
        NoiseProgram p = program;
        if (p == null) {
            synchronized (Nauvis.class) {
                p = program;
                if (p == null) {
                    p = NoiseProgram.nauvis();
                    program = p;
                }
            }
        }
        return p;
    }
}
