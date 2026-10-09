package com.jaguarm.nauvisterrain.fabric.mixin;

import com.jaguarm.nauvisterrain.client.NauvisClient;
import com.jaguarm.nauvisterrain.client.NauvisMapScreen;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Nauvis world type's Customize button opens Factorio's map generator screen. */
@Mixin(WorldCreationUiState.class)
abstract class WorldCreationUiStateMixin {
    @Shadow
    public abstract WorldCreationUiState.WorldTypeEntry getWorldType();

    @Inject(method = "getPresetEditor", at = @At("HEAD"), cancellable = true)
    private void nauvis_terrain$mapScreen(CallbackInfoReturnable<PresetEditor> editor) {
        Holder<WorldPreset> preset = getWorldType().preset();
        if (preset != null && preset.is(NauvisClient.NAUVIS)) {
            editor.setReturnValue(NauvisMapScreen::new);
        }
    }
}
