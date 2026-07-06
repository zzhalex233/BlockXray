package com.zzhalex233.blockxray.common.util;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.oredict.OreDictionary;

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
    private static final Map<Block, Set<Integer>> ITEM_METAS = new HashMap<>();
    private static final Map<Block, Boolean> EXTERNAL_META_BLOCKS = new HashMap<>();
    private static Map<String, ItemStack> cachedVisibleTargetIcons;
    private static Map<String, ItemStack> cachedIcons;
    private static List<String> cachedNames;

    private BlockTargets() {
    }

    public static List<String> names() {
        if (cachedNames == null) {
            List<String> sorted = new ArrayList<>(visibleTargetIcons().keySet());
            sorted.sort(String::compareToIgnoreCase);
            cachedNames = Collections.unmodifiableList(sorted);
        }
        return cachedNames;
    }

    public static Map<String, ItemStack> icons() {
        if (cachedIcons == null) {
            Map<String, ItemStack> icons = new LinkedHashMap<>();
            for (Map.Entry<String, ItemStack> entry : visibleTargetIcons().entrySet()) {
                ItemStack stack = entry.getValue();
                if (stack != null && !stack.isEmpty()) {
                    icons.put(entry.getKey(), stack);
                }
            }
            cachedIcons = Collections.unmodifiableMap(icons);
        }
        return cachedIcons;
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

    public static Set<String> oreNames(String name) {
        Target target = parseTarget(name);
        Block block = target == null ? blockByName(name) : target.block;
        if (block == null || block == Blocks.AIR) {
            return Collections.emptySet();
        }

        Set<String> names = new LinkedHashSet<>();
        Item item = Item.getItemFromBlock(block);
        if (item != null && item != Items.AIR) {
            for (int meta : target == null || target.matchAll ? itemMetas(block) : Collections.singleton(target.meta)) {
                addOreNames(names, new ItemStack(item, 1, meta));
            }
        }
        return names;
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

    public static Set<String> targetsForBlock(World world, BlockPos pos, IBlockState state) {
        if (state == null) {
            return Collections.emptySet();
        }
        return expandTarget(targetId(state.getBlock(), scanMeta(world, pos, state)));
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

    private static Map<String, ItemStack> visibleTargetIcons() {
        if (cachedVisibleTargetIcons != null) {
            return cachedVisibleTargetIcons;
        }

        Map<Block, Map<Integer, ItemStack>> stacksByBlock = new LinkedHashMap<>();
        NonNullList<ItemStack> stacks = NonNullList.create();
        for (CreativeTabs tab : CreativeTabs.CREATIVE_TAB_ARRAY) {
            if (tab == CreativeTabs.HOTBAR) {
                continue;
            }
            try {
                tab.displayAllRelevantItems(stacks);
            } catch (RuntimeException | LinkageError ignored) {
                stacks.clear();
            }
            for (ItemStack stack : stacks) {
                addVisibleStack(stacksByBlock, stack);
            }
            stacks.clear();
        }

        for (Block block : ForgeRegistries.BLOCKS) {
            addVisibleBlockStacks(stacksByBlock, block);
        }
        for (Item item : ForgeRegistries.ITEMS) {
            addVisibleItemStacks(stacksByBlock, item);
        }

        Map<String, ItemStack> targets = new LinkedHashMap<>();
        for (Map.Entry<Block, Map<Integer, ItemStack>> entry : stacksByBlock.entrySet()) {
            ResourceLocation name = entry.getKey().getRegistryName();
            if (name == null) {
                continue;
            }
            boolean single = entry.getValue().size() <= 1;
            for (Map.Entry<Integer, ItemStack> stackEntry : entry.getValue().entrySet()) {
                targets.put(targetId(name, stackEntry.getKey(), single), stackEntry.getValue().copy());
            }
        }
        addVisibleFluidTargets(targets);
        cachedVisibleTargetIcons = Collections.unmodifiableMap(targets);
        return cachedVisibleTargetIcons;
    }

    private static void addVisibleFluidTargets(Map<String, ItemStack> targets) {
        for (Block block : ForgeRegistries.BLOCKS) {
            if (isFluidBlock(block)) {
                for (String target : targetIds(block)) {
                    targets.putIfAbsent(target, ItemStack.EMPTY);
                }
            }
        }
    }

    private static void addVisibleBlockStacks(Map<Block, Map<Integer, ItemStack>> stacksByBlock, Block block) {
        if (block == null || block == Blocks.AIR) {
            return;
        }
        Item item = Item.getItemFromBlock(block);
        if (item == null || item == Items.AIR) {
            return;
        }

        NonNullList<ItemStack> stacks = NonNullList.create();
        for (CreativeTabs tab : item.getCreativeTabs()) {
            try {
                block.getSubBlocks(tab, stacks);
            } catch (RuntimeException | LinkageError ignored) {
                stacks.clear();
            }
            for (ItemStack stack : stacks) {
                addVisibleStack(stacksByBlock, stack);
            }
            stacks.clear();
        }
    }

    private static void addVisibleItemStacks(Map<Block, Map<Integer, ItemStack>> stacksByBlock, Item item) {
        if (item == null || item == Items.AIR) {
            return;
        }

        NonNullList<ItemStack> stacks = NonNullList.create();
        for (CreativeTabs tab : item.getCreativeTabs()) {
            try {
                item.getSubItems(tab, stacks);
            } catch (RuntimeException | LinkageError ignored) {
                stacks.clear();
            }
            for (ItemStack stack : stacks) {
                addVisibleStack(stacksByBlock, stack);
            }
            stacks.clear();
        }
    }

    private static void addVisibleStack(Map<Block, Map<Integer, ItemStack>> stacksByBlock, ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getMetadata() == OreDictionary.WILDCARD_VALUE) {
            return;
        }

        Block block = Block.getBlockFromItem(stack.getItem());
        int meta = stack.getMetadata();
        if (block == Blocks.AIR) {
            block = sameNameBlock(stack);
        }
        if (block == Blocks.AIR) {
            return;
        }
        stacksByBlock.computeIfAbsent(block, ignored -> new LinkedHashMap<>()).putIfAbsent(meta, stack.copy());
    }

    private static Block sameNameBlock(ItemStack stack) {
        ResourceLocation itemName = stack.getItem().getRegistryName();
        if (itemName == null) {
            return Blocks.AIR;
        }
        Block namedBlock = ForgeRegistries.BLOCKS.getValue(itemName);
        return namedBlock == null ? Blocks.AIR : namedBlock;
    }

    private static boolean isFluidBlock(Block block) {
        if (block == null || block == Blocks.AIR) {
            return false;
        }
        if (FluidRegistry.lookupFluidForBlock(block) != null) {
            return true;
        }
        try {
            Material material = block.getDefaultState().getMaterial();
            return material == Material.WATER || material == Material.LAVA;
        } catch (RuntimeException ignored) {
            return false;
        }
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
        Set<Integer> cached = ITEM_METAS.get(block);
        if (cached != null) {
            return cached;
        }

        Set<Integer> metas = new LinkedHashSet<>();
        Item item = Item.getItemFromBlock(block);
        if (item == null || item == Items.AIR) {
            return cacheItemMetas(block, metas);
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
        return cacheItemMetas(block, metas);
    }

    private static Set<Integer> cacheItemMetas(Block block, Set<Integer> metas) {
        Set<Integer> result = Collections.unmodifiableSet(metas);
        ITEM_METAS.put(block, result);
        return result;
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
            metas.add(droppedMeta(state));
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

    private static void addOreNames(Set<String> names, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        for (int oreId : OreDictionary.getOreIDs(stack)) {
            names.add(OreDictionary.getOreName(oreId));
        }
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
        int meta = droppedMeta(state);
        Set<Integer> metas = itemMetas(state.getBlock());
        if (metas.isEmpty() || metas.contains(meta)) {
            return meta;
        }
        return blockMeta(state);
    }

    private static int droppedMeta(IBlockState state) {
        try {
            return state.getBlock().damageDropped(state);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static int blockMeta(IBlockState state) {
        try {
            return state.getBlock().getMetaFromState(state);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    public static int stateMeta(IBlockState state) {
        return state == null ? 0 : targetMeta(state);
    }

    public static int scanMeta(World world, BlockPos pos, IBlockState state) {
        int meta = stateMeta(state);
        if (world == null || pos == null || state == null || !requiresExternalMeta(state.getBlock())) {
            return meta;
        }

        ItemStack stack = pickStack(world, pos, state);
        if (stack.isEmpty()) {
            return meta;
        }

        Block stackBlock = Block.getBlockFromItem(stack.getItem());
        if (stackBlock == Blocks.AIR) {
            stackBlock = sameNameBlock(stack);
        }
        return stackBlock == state.getBlock() ? stack.getMetadata() : meta;
    }

    private static ItemStack pickStack(World world, BlockPos pos, IBlockState state) {
        try {
            RayTraceResult hit = new RayTraceResult(new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D), EnumFacing.UP, pos);
            ItemStack stack = state.getBlock().getPickBlock(state, hit, world, pos, null);
            return stack == null ? ItemStack.EMPTY : stack;
        } catch (RuntimeException | LinkageError ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static boolean requiresExternalMeta(Block block) {
        Boolean cached = EXTERNAL_META_BLOCKS.get(block);
        if (cached == null) {
            cached = itemMetas(block).size() > stateMetas(block).size();
            EXTERNAL_META_BLOCKS.put(block, cached);
        }
        return cached;
    }

    private static Set<Integer> stateMetas(Block block) {
        Set<Integer> metas = new LinkedHashSet<>();
        if (block == null || block == Blocks.AIR) {
            return metas;
        }
        for (IBlockState state : block.getBlockState().getValidStates()) {
            metas.add(targetMeta(state));
        }
        if (metas.isEmpty()) {
            metas.add(0);
        }
        return metas;
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

        @Override
        public boolean requiresWorldMeta(IBlockState state) {
            if (state == null) {
                return false;
            }
            MetaMatcher matcher = blocks.get(state.getBlock());
            return matcher != null && matcher.hasSpecificMetas() && requiresExternalMeta(state.getBlock());
        }

        @Override
        public boolean matches(IBlockState state, int meta) {
            if (state == null) {
                return false;
            }
            MetaMatcher matcher = blocks.get(state.getBlock());
            return matcher != null && matcher.matches(resolvedMeta(state, meta));
        }

        @Override
        public Set<String> matchingNames(IBlockState state, int meta) {
            if (state == null) {
                return Collections.emptySet();
            }
            MetaMatcher matcher = blocks.get(state.getBlock());
            return matcher == null ? Collections.emptySet() : matcher.matchingNames(resolvedMeta(state, meta));
        }

        private int resolvedMeta(IBlockState state, int meta) {
            return meta == ProspectorMatcher.UNKNOWN_META ? targetMeta(state) : meta;
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

        private boolean hasSpecificMetas() {
            return !namesByMeta.isEmpty();
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
