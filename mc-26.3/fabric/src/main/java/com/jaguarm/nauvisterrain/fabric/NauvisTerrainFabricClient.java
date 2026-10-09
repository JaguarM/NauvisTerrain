package com.jaguarm.nauvisterrain.fabric;

import com.jaguarm.nauvisterrain.client.NauvisClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

/** Fabric's hooks for {@link NauvisClient}; the map generator screen opens through a mixin. */
public final class NauvisTerrainFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> NauvisClient.nauvisFirst(screen));
    }
}
