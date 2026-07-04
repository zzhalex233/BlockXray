package com.zzhalex233.blockxray.common.util;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BlockTargets {
    private static final String META_SEPARATOR = "@";

    private BlockTargets() {
    }

    public static List<String> names() {
        Set<String> names = new LinkedHashSet<>();
        for (Block block : ForgeRegistries.BLOCKS) {
            names.addAll(targetIds(block));
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String::compareToIgnoreCase);
        return sorted;
    }

    public static Map<String, ItemStack> icons() {
        Map<String, ItemStack> icons = new LinkedHashMap<>();
        for (String name : names()) {
            Target target = parseTarget(name);
            if (target != null) {
                ItemStack stack = iconStack(target);
                if (!stack.isEmpty()) {
                    icons.put(name, stack);
                }
            }
        }
        return icons;
    }

    public static String displayName(String name) {
        Target target = parseTarget(name);
        Block block = target == null ? blockByName(name) : target.block;
        if (block == null || block == Blocks.AIR) {
            return name;
        }

        if (target != null) {
            ItemStack stack = iconStack(target);
            if (!stack.isEmpty()) {
                String itemName = stack.getDisplayName();
                if (validDisplayName(itemName, name)) {
                    return itemName;
                }
            }
        }

        String blockName = block.getLocalizedName();
        return validDisplayName(blockName, block.getTranslationKey() + ".name") ? blockName : name;
    }

    public static ProspectorMatcher matcher(Set<String> selectedBlocks) {
        if (selectedBlocks == null || selectedBlocks.isEmpty()) {
            return Matcher.EMPTY;
        }

        Map<Block, MetaMatcher> blocks = new HashMap<>();
        for (String name : selectedBlocks) {
            for (String targetName : expandTarget(name)) {
                Target target = parseTarget(targetName);
                if (target != null) {
                    MetaMatcher matcher = blocks.computeIfAbsent(target.block, ignored -> new MetaMatcher());
                    if (target.matchAll) {
                        matcher.matchAll(targetName);
                    } else {
                        matcher.match(target.meta, targetName);
                    }
                }
            }
        }
        return blocks.isEmpty() ? Matcher.EMPTY : new Matcher(blocks);
    }

    public static boolean isValidName(String name) {
        return !expandTarget(name).isEmpty();
    }

    public static Set<String> targetsForState(IBlockState state) {
        if (state == null) {
            return Collections.emptySet();
        }
        return expandTarget(targetId(state.getBlock(), targetMeta(state)));
    }

    public static Set<String> expandTarget(String name) {
        Set<String> targets = new LinkedHashSet<>();
        Target target = parseTarget(name);
        if (target != null) {
            Set<String> ids = targetIds(target.block);
            if (target.matchAll) {
                ResourceLocation blockName = target.block.getRegistryName();
                if (blockName != null && ids.contains(blockName.toString())) {
                    targets.add(blockName.toString());
                }
                return targets;
            }
            Set<Integer> metas = itemMetas(target.block);
            String normalized = targetId(target.block, target.meta, metas.size() <= 1);
            if (ids.contains(normalized)) {
                targets.add(normalized);
            } else {
                String itemMetaTarget = targetId(target.block, targetMeta(stateFromMeta(target.block, target.meta)), metas.size() <= 1);
                if (ids.contains(itemMetaTarget)) {
                    targets.add(itemMetaTarget);
                }
            }
            return targets;
        }

        Block block = blockByName(name);
        if (block != null && block != Blocks.AIR) {
            targets.addAll(targetIds(block));
        }
        return targets;
    }

    public static IBlockState state(String name) {
        Target target = parseTarget(name);
        if (target != null) {
            return target.matchAll ? target.block.getDefaultState() : stateFromMeta(target.block, target.meta);
        }
        Block block = blockByName(name);
        return block == null || block == Blocks.AIR ? null : block.getDefaultState();
    }

    private static Set<String> targetIds(Block block) {
        Set<String> targets = new LinkedHashSet<>();
        ResourceLocation name = block == null ? null : block.getRegistryName();
        if (name == null || block == Blocks.AIR) {
            return targets;
        }
        Set<Integer> metas = itemMetas(block);
        boolean single = metas.size() <= 1;
        for (int meta : metas) {
            targets.add(targetId(name, meta, single));
        }
        if (targets.isEmpty()) {
            targets.add(name.toString());
        }
        return targets;
    }

    private static Set<Integer> itemMetas(Block block) {
        Set<Integer> metas = new LinkedHashSet<>();
        Item item = Item.getItemFromBlock(block);
        if (item == null || item == Items.AIR) {
            return metas;
        }

        NonNullList<ItemStack> stacks = NonNullList.create();
        collectItemStacks(item, stacks);
        if (stacks.isEmpty()) {
            collectBlockStacks(block, stacks);
        }

        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && Block.getBlockFromItem(stack.getItem()) == block) {
                metas.add(stack.getMetadata());
            }
        }
        if (item.getHasSubtypes()) {
            collectDroppedMetas(block, metas);
        }
        if (metas.isEmpty()) {
            metas.add(0);
        }
        return metas;
    }

    private static void collectItemStacks(Item item, NonNullList<ItemStack> stacks) {
        try {
            item.getSubItems(CreativeTabs.SEARCH, stacks);
        } catch (RuntimeException ignored) {
        }
    }

    private static void collectBlockStacks(Block block, NonNullList<ItemStack> stacks) {
        try {
            block.getSubBlocks(CreativeTabs.SEARCH, stacks);
        } catch (RuntimeException ignored) {
        }
    }

    private static void collectDroppedMetas(Block block, Set<Integer> metas) {
        for (IBlockState state : block.getBlockState().getValidStates()) {
            metas.add(targetMeta(state));
        }
    }

    private static ItemStack iconStack(Target target) {
        int meta = target.matchAll ? 0 : target.meta;
        Item item = Item.getItemFromBlock(target.block);
        if (item != null && item != Items.AIR) {
            ItemStack stack = new ItemStack(item, 1, meta);
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        try {
            ItemStack stack = new ItemStack(target.block, 1, meta);
            return stack.isEmpty() ? ItemStack.EMPTY : stack;
        } catch (RuntimeException ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static boolean validDisplayName(String name, String missingValue) {
        return name != null && !name.trim().isEmpty() && !name.equals(missingValue);
    }

    private static String targetId(Block block, int meta) {
        ResourceLocation name = block.getRegistryName();
        return name == null ? "" : targetId(name, meta, itemMetas(block).size() <= 1);
    }

    private static String targetId(Block block, int meta, boolean omitMeta) {
        ResourceLocation name = block.getRegistryName();
        return name == null ? "" : targetId(name, meta, omitMeta);
    }

    private static String targetId(ResourceLocation name, int meta, boolean omitMeta) {
        return omitMeta ? name.toString() : name + META_SEPARATOR + meta;
    }

    private static Target parseTarget(String name) {
        int metaSeparator = name == null ? -1 : name.lastIndexOf(META_SEPARATOR);
        if (metaSeparator <= 0 || metaSeparator == name.length() - 1) {
            Block block = blockByName(name);
            if (block == null || block == Blocks.AIR) {
                return null;
            }
            Set<Integer> metas = itemMetas(block);
            if (metas.isEmpty()) {
                return new Target(block, 0, true);
            }
            return metas.size() == 1 ? new Target(block, metas.iterator().next()) : null;
        }

        Block block = blockByName(name.substring(0, metaSeparator));
        if (block == null || block == Blocks.AIR) {
            return null;
        }
        try {
            return new Target(block, Integer.parseInt(name.substring(metaSeparator + 1)));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static IBlockState stateFromMeta(Block block, int meta) {
        try {
            return block.getStateFromMeta(meta);
        } catch (RuntimeException ignored) {
            return block.getDefaultState();
        }
    }

    private static int targetMeta(IBlockState state) {
        try {
            return state.getBlock().damageDropped(state);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static Block blockByName(String name) {
        try {
            return ForgeRegistries.BLOCKS.getValue(new ResourceLocation(name));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static final class Matcher implements ProspectorMatcher {
        private static final Matcher EMPTY = new Matcher(new HashMap<>());

        private final Map<Block, MetaMatcher> blocks;

        private Matcher(Map<Block, MetaMatcher> blocks) {
            this.blocks = blocks;
        }

        @Override
        public boolean isEmpty() {
            return blocks.isEmpty();
        }

        @Override
        public boolean matches(IBlockState state) {
            if (state == null) {
                return false;
            }
            MetaMatcher matcher = blocks.get(state.getBlock());
            return matcher != null && matcher.matches(targetMeta(state));
        }

        @Override
        public Set<String> matchingNames(IBlockState state) {
            if (state == null) {
                return Collections.emptySet();
            }
            MetaMatcher matcher = blocks.get(state.getBlock());
            return matcher == null ? Collections.emptySet() : matcher.matchingNames(targetMeta(state));
        }
    }

    private static final class MetaMatcher {
        private final Map<Integer, Set<String>> namesByMeta = new HashMap<>();
        private final Set<String> allNames = new LinkedHashSet<>();

        private void match(int meta, String name) {
            namesByMeta.computeIfAbsent(meta, ignored -> new LinkedHashSet<>()).add(name);
        }

        private void matchAll(String name) {
            allNames.add(name);
        }

        private boolean matches(int meta) {
            return !allNames.isEmpty() || namesByMeta.containsKey(meta);
        }

        private Set<String> matchingNames(int meta) {
            Set<String> names = namesByMeta.get(meta);
            if (allNames.isEmpty()) {
                return names == null ? Collections.emptySet() : names;
            }
            Set<String> result = new LinkedHashSet<>(allNames);
            if (names != null) {
                result.addAll(names);
            }
            return result;
        }
    }

    private static final class Target {
        private final Block block;
        private final int meta;
        private final boolean matchAll;

        private Target(Block block, int meta) {
            this(block, meta, false);
        }

        private Target(Block block, int meta, boolean matchAll) {
            this.block = block;
            this.meta = meta;
            this.matchAll = matchAll;
        }
    }
}
