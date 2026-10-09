package com.jaguarm.nauvisterrain.fabric;

import com.jaguarm.nauvisterrain.world.AutoplacePlacement;
import com.jaguarm.nauvisterrain.world.BoulderFeature;
import com.jaguarm.nauvisterrain.world.DynamicTreesFeature;
import com.jaguarm.nauvisterrain.world.Nauvis;
import com.jaguarm.nauvisterrain.world.NauvisGenerator;
import com.jaguarm.nauvisterrain.world.TerrainBlocks;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Factorio's Nauvis terrain as a Minecraft world type, on Fabric. */
public final class NauvisTerrainFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("nauvis"), NauvisGenerator.CODEC);
        Registry.register(BuiltInRegistries.PLACEMENT_MODIFIER_TYPE, id("autoplace"), AutoplacePlacement.CODEC);
        Registry.register(BuiltInRegistries.FEATURE_TYPE, id("boulder"), BoulderFeature.CODEC);
        Registry.register(BuiltInRegistries.FEATURE_TYPE, id("dynamic_trees"), DynamicTreesFeature.CODEC);
        TerrainBlocks.register((path, properties) -> {
            Block block = Blocks.register(ResourceKey.create(Registries.BLOCK, id(path)), properties.apply(BlockBehaviour.Properties.of()));
            ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id(path));
            BlockItem item = new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix());
            item.registerBlocks(Item.BY_BLOCK, item);
            Registry.register(BuiltInRegistries.ITEM, itemKey, item);
            return BuiltInRegistries.BLOCK.wrapAsHolder(block);
        });
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.NATURAL_BLOCKS).register(output -> {
            TerrainBlocks.tiles().values().forEach(block -> output.accept(block.value()));
            output.accept(TerrainBlocks.cliff().value());
        });
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Nauvis.MOD_ID, path);
    }
}
