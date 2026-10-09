package com.jaguarm.nauvisterrain.fabric;

import com.jaguarm.nauvisterrain.world.AutoplacePlacement;
import com.jaguarm.nauvisterrain.world.BoulderFeature;
import com.jaguarm.nauvisterrain.world.Nauvis;
import com.jaguarm.nauvisterrain.world.NauvisGenerator;
import com.jaguarm.nauvisterrain.world.TerrainBlocks;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Factorio's Nauvis terrain as a Minecraft world type, on Fabric. A tile's block keeps itself under a
 * growing tree through a mixin of {@code TrunkPlacer}.
 */
public final class NauvisTerrainFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("nauvis"), NauvisGenerator.CODEC);
        Registry.register(BuiltInRegistries.PLACEMENT_MODIFIER_TYPE, id("autoplace"), AutoplacePlacement.TYPE);
        Registry.register(BuiltInRegistries.FEATURE, id("boulder"), new BoulderFeature());
        TerrainBlocks.register(new TerrainBlocks.Registrar() {
            @Override
            public Holder<Block> tile(String path, BlockBehaviour.Properties properties) {
                return block(path, properties);
            }

            @Override
            public Holder<Block> block(String path, BlockBehaviour.Properties properties) {
                Block block = Registry.register(BuiltInRegistries.BLOCK, id(path), new Block(properties));
                Items.registerBlock(block);
                return BuiltInRegistries.BLOCK.wrapAsHolder(block);
            }
        });
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.NATURAL_BLOCKS).register(entries -> {
            TerrainBlocks.tiles().values().forEach(block -> entries.accept(block.value()));
            entries.accept(TerrainBlocks.cliff().value());
        });
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(Nauvis.MOD_ID, path);
    }
}
