package com.zzhalex233.blockxray.common.item;

import com.zzhalex233.blockxray.BlockXray;
import com.zzhalex233.blockxray.Reference;
import com.zzhalex233.blockxray.common.util.BlockTargets;
import com.zzhalex233.blockxray.config.BlockXrayConfig;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import java.util.LinkedHashSet;
import java.util.Set;

public class ItemProspector extends Item {
    private static final String TAG_SELECTED_TARGETS = "SelectedOres";
    private static final String TAG_RANGE = "Range";
    public static final int DEFAULT_RANGE = 1;
    public static final int MIN_RANGE = 1;
    public static final int DEFAULT_MAX_RANGE = 8;
    private final boolean blockProspector;

    public ItemProspector(String name, boolean blockProspector) {
        this.blockProspector = blockProspector;
        setRegistryName(Reference.MOD_ID, name);
        setTranslationKey(Reference.MOD_ID + "." + name);
        setCreativeTab(CreativeTabs.TOOLS);
        setMaxStackSize(1);
    }

    public boolean isBlockProspector() {
        return blockProspector;
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) {
            BlockXray.proxy.openProspectorGui(stack, hand);
        }
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }

    public Set<String> getSelectedTargets(ItemStack stack) {
        Set<String> targets = new LinkedHashSet<>();
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            return targets;
        }
        NBTTagList list = tag.getTagList(TAG_SELECTED_TARGETS, Constants.NBT.TAG_STRING);
        for (int i = 0; i < list.tagCount(); i++) {
            String target = list.getStringTagAt(i);
            if (isValidTarget(target)) {
                targets.add(target);
            }
        }
        return targets;
    }

    public static int getRange(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return clampRange(tag == null || !tag.hasKey(TAG_RANGE) ? DEFAULT_RANGE : tag.getInteger(TAG_RANGE));
    }

    public void setSettings(ItemStack stack, Set<String> selectedTargets, int range) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }

        NBTTagList list = new NBTTagList();
        for (String target : selectedTargets) {
            if (isValidTarget(target)) {
                list.appendTag(new net.minecraft.nbt.NBTTagString(target));
            }
        }
        tag.setTag(TAG_SELECTED_TARGETS, list);
        tag.setInteger(TAG_RANGE, clampRange(range));
    }

    public static int clampRange(int range) {
        return Math.max(MIN_RANGE, Math.min(BlockXrayConfig.prospectorMaxRange(), range));
    }

    public static int getMaxRange() {
        return BlockXrayConfig.prospectorMaxRange();
    }

    private boolean isValidTarget(String target) {
        return target != null && (blockProspector ? BlockTargets.isValidName(target) : target.startsWith("ore"));
    }
}
