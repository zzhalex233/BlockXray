package com.zzhalex233.blockxray.network;

import com.zzhalex233.blockxray.Reference;
import com.zzhalex233.blockxray.network.message.MessageProspectorSettings;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public final class BlockXrayNetwork {
    private static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(Reference.MOD_ID);
    private static boolean registered;
    private static int nextId;

    private BlockXrayNetwork() {
    }

    public static void registerMessages() {
        if (registered) {
            return;
        }
        CHANNEL.registerMessage(MessageProspectorSettings.Handler.class, MessageProspectorSettings.class, nextId++, Side.SERVER);
        registered = true;
    }

    public static SimpleNetworkWrapper getChannel() {
        return CHANNEL;
    }
}
