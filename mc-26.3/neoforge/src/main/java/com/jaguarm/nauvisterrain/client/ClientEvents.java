package com.jaguarm.nauvisterrain.client;

import com.jaguarm.nauvisterrain.world.Nauvis;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** NeoForge's hooks for {@link NauvisClient}. */
@EventBusSubscriber(modid = Nauvis.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {
    }

    @SubscribeEvent
    static void presetEditors(RegisterPresetEditorsEvent event) {
        event.register(NauvisClient.NAUVIS, NauvisMapScreen::new);
    }

    @SubscribeEvent
    static void nauvisFirst(ScreenEvent.Init.Post event) {
        NauvisClient.nauvisFirst(event.getScreen());
    }
}
