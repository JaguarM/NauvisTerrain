package com.jaguarm.nauvisterrain.client;

import com.jaguarm.nauvisterrain.world.Nauvis;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The client's part: a new world is a Nauvis world unless the player picks another type, and the
 * Nauvis world type's Customize button opens Factorio's map generator screen. Each loader hooks
 * these in.
 */
public final class NauvisClient {
    public static final ResourceKey<WorldPreset> NAUVIS =
            ResourceKey.create(Registries.WORLD_PRESET, Identifier.fromNamespaceAndPath(Nauvis.MOD_ID, "nauvis"));
    /** Create-world screens already given their first world type; a screen is initialised again on every resize. */
    private static final Set<Screen> CHOSEN = Collections.newSetFromMap(new WeakHashMap<>());

    private NauvisClient() {
    }

    /** A create-world screen opened for a new world, not to re-create one, starts on Nauvis instead of Default. */
    public static void nauvisFirst(Screen screen) {
        if (!(screen instanceof CreateWorldScreen createWorld) || !CHOSEN.add(createWorld)) {
            return;
        }
        WorldCreationUiState state = createWorld.getUiState();
        if (!state.getSeed().isEmpty() || !is(state.getWorldType(), WorldPresets.NORMAL)) {
            return;
        }
        for (WorldCreationUiState.WorldTypeEntry entry : state.getNormalPresetList()) {
            if (is(entry, NAUVIS)) {
                state.setWorldType(entry);
                return;
            }
        }
    }

    private static boolean is(WorldCreationUiState.WorldTypeEntry entry, ResourceKey<WorldPreset> key) {
        return entry.preset() != null && entry.preset().is(key);
    }
}
