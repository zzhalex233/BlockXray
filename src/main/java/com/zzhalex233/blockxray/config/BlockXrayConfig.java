package com.zzhalex233.blockxray.config;

import com.zzhalex233.blockxray.Reference;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = Reference.MOD_ID, name = Reference.MOD_ID)
public final class BlockXrayConfig {
    @Config.Name("prospectorMaxChunkRadius")
    @Config.Comment("Maximum selectable chunk radius for the Prospector.")
    @Config.RangeInt(min = 1, max = 256)
    public static int prospectorMaxChunkRadius = 8;

    private BlockXrayConfig() {
    }

    public static int prospectorMaxChunkRadius() {
        return Math.max(1, Math.min(256, prospectorMaxChunkRadius));
    }

    public static void sync() {
        ConfigManager.sync(Reference.MOD_ID, Config.Type.INSTANCE);
    }

    @Mod.EventBusSubscriber(modid = Reference.MOD_ID)
    public static final class EventHandler {
        private EventHandler() {
        }

        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (Reference.MOD_ID.equals(event.getModID())) {
                sync();
            }
        }
    }
}
