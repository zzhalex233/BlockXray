package com.zzhalex233.blockxray.network.message;

import com.zzhalex233.blockxray.common.item.ItemProspector;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.LinkedHashSet;
import java.util.Set;

public class MessageProspectorSettings implements IMessage {
    private EnumHand hand;
    private int range;
    private Set<String> selectedOres = new LinkedHashSet<>();

    public MessageProspectorSettings() {
    }

    public MessageProspectorSettings(EnumHand hand, int range, Set<String> selectedOres) {
        this.hand = hand;
        this.range = range;
        this.selectedOres.addAll(selectedOres);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        hand = buf.readBoolean() ? EnumHand.OFF_HAND : EnumHand.MAIN_HAND;
        range = buf.readInt();
        selectedOres.clear();
        int count = buf.readInt();
        for (int i = 0; i < count; i++) {
            String ore = ByteBufUtils.readUTF8String(buf);
            if (ore.startsWith("ore")) {
                selectedOres.add(ore);
            }
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(hand == EnumHand.OFF_HAND);
        buf.writeInt(range);
        buf.writeInt(selectedOres.size());
        for (String ore : selectedOres) {
            ByteBufUtils.writeUTF8String(buf, ore);
        }
    }

    public static class Handler implements IMessageHandler<MessageProspectorSettings, IMessage> {
        @Override
        public IMessage onMessage(MessageProspectorSettings message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                ItemStack stack = player.getHeldItem(message.hand);
                if (stack.getItem() instanceof ItemProspector) {
                    ItemProspector.setSettings(stack, message.selectedOres, message.range);
                    player.inventoryContainer.detectAndSendChanges();
                }
            });
            return null;
        }
    }
}
