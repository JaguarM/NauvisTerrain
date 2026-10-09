package com.jaguarm.nauvisterrain;

import com.jaguarm.nauvisterrain.test.TerrainGameTests;
import com.jaguarm.nauvisterrain.world.AutoplacePlacement;
import com.jaguarm.nauvisterrain.world.BoulderFeature;
import com.jaguarm.nauvisterrain.world.DynamicTreesFeature;
import com.jaguarm.nauvisterrain.world.Nauvis;
import com.jaguarm.nauvisterrain.world.NauvisGenerator;
import com.jaguarm.nauvisterrain.world.TerrainBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Factorio's Nauvis terrain as a Minecraft world type, on NeoForge. */
@Mod(Nauvis.MOD_ID)
public final class NauvisTerrain {
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, Nauvis.MOD_ID);
    private static final DeferredRegister<MapCodec<? extends PlacementModifier>> PLACEMENTS =
            DeferredRegister.create(Registries.PLACEMENT_MODIFIER_TYPE, Nauvis.MOD_ID);
    private static final DeferredRegister<MapCodec<? extends Feature>> FEATURES =
            DeferredRegister.create(Registries.FEATURE_TYPE, Nauvis.MOD_ID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Nauvis.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Nauvis.MOD_ID);

    static {
        CHUNK_GENERATORS.register("nauvis", () -> NauvisGenerator.CODEC);
        PLACEMENTS.register("autoplace", () -> AutoplacePlacement.CODEC);
        FEATURES.register("boulder", () -> BoulderFeature.CODEC);
        FEATURES.register("dynamic_trees", () -> DynamicTreesFeature.CODEC);
        TerrainBlocks.register((path, properties) -> {
            DeferredBlock<Block> block = BLOCKS.registerSimpleBlock(path, properties);
            ITEMS.registerSimpleBlockItem(block);
            return block;
        });
    }

    public NauvisTerrain(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        CHUNK_GENERATORS.register(modBus);
        PLACEMENTS.register(modBus);
        FEATURES.register(modBus);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, event -> {
            if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
                TerrainBlocks.tiles().values().forEach(block -> event.accept(block.value()));
                event.accept(TerrainBlocks.cliff().value());
            }
        });
        TerrainGameTests.register(modBus);
    }
}
