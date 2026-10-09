package com.jaguarm.nauvisterrain.world;

import com.jaguarm.nauvisterrain.noise.NoiseProgram;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;

/** Nauvis's noise program, read from the jar once, and the names its prototypes go by in Minecraft. */
public final class Nauvis {
    /** The namespace of everything the mod adds. */
    public static final String MOD_ID = "nauvis_terrain";

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

    /** A Factorio name as a Minecraft path: `grass-1` is `grass_1` (CLAUDE.md, rule 3). */
    public static String id(String factorioName) {
        return factorioName.replace('-', '_');
    }

    /** A tile that Factorio's collision layers call water. */
    public static boolean isWater(Prototype tile) {
        return tile.collisionMask().layers().contains("water_tile");
    }
}
