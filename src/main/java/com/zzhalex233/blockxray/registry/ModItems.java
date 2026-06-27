package com.zzhalex233.blockxray.registry;

import com.zzhalex233.blockxray.Reference;
import com.zzhalex233.blockxray.common.item.ItemProspector;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public final class ModItems {
    public static final ItemProspector ORE_PROSPECTOR = new ItemProspector("oreprospector", false);
    public static final ItemProspector BLOCK_PROSPECTOR = new ItemProspector("blockprospector", true);

    private ModItems() {
    }

    @SubscribeEvent
    public static void onRegisterItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().register(ORE_PROSPECTOR);
        event.getRegistry().register(BLOCK_PROSPECTOR);
    }

    @SubscribeEvent
    public static void onMissingItemMappings(RegistryEvent.MissingMappings<Item> event) {
        for (RegistryEvent.MissingMappings.Mapping<Item> mapping : event.getMappings()) {
            if ((Reference.MOD_ID + ":prospector").equals(mapping.key.toString())) {
                mapping.remap(ORE_PROSPECTOR);
            }
        }
    }

    @SideOnly(Side.CLIENT)
    @SubscribeEvent
    public static void onModelRegistry(ModelRegistryEvent event) {
        ModelLoader.setCustomModelResourceLocation(ORE_PROSPECTOR, 0, new ModelResourceLocation(ORE_PROSPECTOR.getRegistryName(), "inventory"));
        ModelLoader.setCustomModelResourceLocation(BLOCK_PROSPECTOR, 0, new ModelResourceLocation(BLOCK_PROSPECTOR.getRegistryName(), "inventory"));
    }
}
