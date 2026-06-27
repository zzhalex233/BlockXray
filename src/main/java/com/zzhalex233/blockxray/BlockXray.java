package com.zzhalex233.blockxray;

import com.zzhalex233.blockxray.proxy.CommonProxy;
import com.zzhalex233.blockxray.compat.CodeChickenLibCompat;
import com.zzhalex233.blockxray.network.BlockXrayNetwork;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Reference.MOD_ID, name = Reference.MOD_NAME, version = Reference.VERSION)
public class BlockXray {

    public static final Logger LOGGER = LogManager.getLogger(Reference.MOD_NAME);

    @Mod.Instance(Reference.MOD_ID)
    public static BlockXray INSTANCE;

    @SidedProxy(
            modId = Reference.MOD_ID,
            clientSide = Reference.PACKAGE + ".proxy.ClientProxy",
            serverSide = Reference.PACKAGE + ".proxy.CommonProxy"
    )
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        CodeChickenLibCompat.apply();
        BlockXrayNetwork.registerMessages();
        proxy.preInit(event);
        LOGGER.info("{} initialized.", Reference.MOD_NAME);
    }
}
