package com.jaguarm.nauvisterrain;

import com.jaguarm.nauvisterrain.test.TerrainGameTests;
import com.jaguarm.nauvisterrain.world.AutoplacePlacement;
import com.jaguarm.nauvisterrain.world.BoulderFeature;
import com.jaguarm.nauvisterrain.world.NauvisGenerator;
import com.jaguarm.nauvisterrain.world.TerrainBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Factorio's Nauvis terrain as a Minecraft world type. */
@Mod(NauvisTerrain.MOD_ID)
public final class NauvisTerrain {
    public static final String MOD_ID = "nauvis_terrain";

    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, MOD_ID);

    private static final DeferredRegister<PlacementModifierType<?>> PLACEMENTS =
            DeferredRegister.create(Registries.PLACEMENT_MODIFIER_TYPE, MOD_ID);
    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, MOD_ID);

    static {
        CHUNK_GENERATORS.register("nauvis", () -> NauvisGenerator.CODEC);
        PLACEMENTS.register("autoplace", () -> AutoplacePlacement.TYPE);
        FEATURES.register("boulder", BoulderFeature::new);
    }

    public NauvisTerrain(IEventBus modBus) {
        TerrainBlocks.BLOCKS.register(modBus);
        TerrainBlocks.ITEMS.register(modBus);
        CHUNK_GENERATORS.register(modBus);
        PLACEMENTS.register(modBus);
        FEATURES.register(modBus);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, event -> {
            if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
                TerrainBlocks.TILES.values().forEach(event::accept);
                event.accept(TerrainBlocks.CLIFF);
            }
        });
        TerrainGameTests.register(modBus);
    }
}
