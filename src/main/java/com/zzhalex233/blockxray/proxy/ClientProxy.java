package com.zzhalex233.blockxray.proxy;

import com.zzhalex233.blockxray.client.gui.GuiProspector;
import com.zzhalex233.blockxray.client.render.ProspectorXrayRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {
    @Override
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(ProspectorXrayRenderer.INSTANCE);
    }

    @Override
    public void openProspectorGui(ItemStack stack, EnumHand hand) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiProspector(stack, hand));
    }
}
