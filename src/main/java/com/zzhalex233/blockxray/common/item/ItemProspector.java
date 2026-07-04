package com.zzhalex233.blockxray.common.item;

import com.zzhalex233.blockxray.BlockXray;
import com.zzhalex233.blockxray.Reference;
import com.zzhalex233.blockxray.common.util.BlockTargets;
import com.zzhalex233.blockxray.common.util.OreDictionaryBlocks;
import com.zzhalex233.blockxray.config.BlockXrayConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import java.util.LinkedHashSet;
import java.util.Set;

public class ItemProspector extends Item {
    private static final String TAG_SELECTED_TARGETS = "SelectedOres";
    private static final String TAG_RANGE = "Range";
    public static final int DEFAULT_RANGE = 1;
    public static final int MIN_RANGE = 1;
    public static final int DEFAULT_MAX_CHUNK_RADIUS = 8;
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

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!player.isSneaking()) {
            return EnumActionResult.PASS;
        }

        Set<String> targets = targetsForState(world.getBlockState(pos));
        if (targets.isEmpty()) {
            return EnumActionResult.FAIL;
        }
        if (!world.isRemote) {
            ItemStack stack = player.getHeldItem(hand);
            Set<String> selected = getSelectedTargets(stack);
            boolean remove = selected.containsAll(targets);
            if (remove) {
                selected.removeAll(targets);
            } else {
                selected.addAll(targets);
            }
            setSettings(stack, selected, getRange(stack));
            player.inventoryContainer.detectAndSendChanges();
            if (!remove) {
                world.playSound(null, pos, SoundEvents.BLOCK_NOTE_PLING, SoundCategory.PLAYERS, 0.45F, 1.25F);
            }
        }
        return EnumActionResult.SUCCESS;
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
            addValidTargets(targets, target);
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
            for (String validTarget : validTargets(target)) {
                list.appendTag(new net.minecraft.nbt.NBTTagString(validTarget));
            }
        }
        tag.setTag(TAG_SELECTED_TARGETS, list);
        tag.setInteger(TAG_RANGE, clampRange(range));
    }

    public static int clampRange(int range) {
        return Math.max(MIN_RANGE, Math.min(BlockXrayConfig.prospectorMaxChunkRadius(), range));
    }

    public static int getMaxRange() {
        return BlockXrayConfig.prospectorMaxChunkRadius();
    }

    private void addValidTargets(Set<String> targets, String target) {
        targets.addAll(validTargets(target));
    }

    private Set<String> validTargets(String target) {
        if (blockProspector) {
            return BlockTargets.expandTarget(target);
        }
        return OreDictionaryBlocks.expandTarget(target);
    }

    private Set<String> targetsForState(IBlockState state) {
        return blockProspector ? BlockTargets.targetsForState(state) : OreDictionaryBlocks.targetsForState(state);
    }
}
