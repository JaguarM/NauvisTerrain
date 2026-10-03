package com.jaguarm.nauvisterrain.client;

import com.jaguarm.nauvisterrain.NauvisTerrain;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent;

/** The client's part: the Nauvis world type's Customize button opens Factorio's map generator screen. */
@EventBusSubscriber(modid = NauvisTerrain.MOD_ID, value = Dist.CLIENT)
public final class NauvisClient {
    private NauvisClient() {
    }

    @SubscribeEvent
    static void presetEditors(RegisterPresetEditorsEvent event) {
        event.register(ResourceKey.create(Registries.WORLD_PRESET, Identifier.fromNamespaceAndPath(NauvisTerrain.MOD_ID, "nauvis")),
                NauvisMapScreen::new);
    }
}
