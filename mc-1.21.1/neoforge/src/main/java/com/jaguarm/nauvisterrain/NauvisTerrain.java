package com.jaguarm.nauvisterrain;

import com.jaguarm.nauvisterrain.test.TerrainGameTests;
import com.jaguarm.nauvisterrain.world.AutoplacePlacement;
import com.jaguarm.nauvisterrain.world.BoulderFeature;
import com.jaguarm.nauvisterrain.world.DynamicTreesFeature;
import com.jaguarm.nauvisterrain.world.Nauvis;
import com.jaguarm.nauvisterrain.world.NauvisGenerator;
import com.jaguarm.nauvisterrain.world.TerrainBlocks;
import com.jaguarm.nauvisterrain.world.TileBlock;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
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
    private static final DeferredRegister<PlacementModifierType<?>> PLACEMENTS =
            DeferredRegister.create(Registries.PLACEMENT_MODIFIER_TYPE, Nauvis.MOD_ID);
    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, Nauvis.MOD_ID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Nauvis.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Nauvis.MOD_ID);

    static {
        CHUNK_GENERATORS.register("nauvis", () -> NauvisGenerator.CODEC);
        PLACEMENTS.register("autoplace", () -> AutoplacePlacement.TYPE);
        FEATURES.register("boulder", BoulderFeature::new);
        FEATURES.register("dynamic_trees", DynamicTreesFeature::new);
        TerrainBlocks.register(new TerrainBlocks.Registrar() {
            @Override
            public Holder<Block> tile(String path, BlockBehaviour.Properties properties) {
                return withItem(BLOCKS.<Block>registerBlock(path, TileBlock::new, properties));
            }

            @Override
            public Holder<Block> block(String path, BlockBehaviour.Properties properties) {
                return withItem(BLOCKS.registerSimpleBlock(path, properties));
            }
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

    private static DeferredBlock<Block> withItem(DeferredBlock<Block> block) {
        ITEMS.registerSimpleBlockItem(block);
        return block;
    }
}
