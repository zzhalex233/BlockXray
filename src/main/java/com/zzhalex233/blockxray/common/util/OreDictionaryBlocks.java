package com.zzhalex233.blockxray.common.util;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.oredict.OreDictionary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OreDictionaryBlocks {
    private static final String TARGET_SEPARATOR = "|";
    private static final String META_SEPARATOR = "@";

    private OreDictionaryBlocks() {
    }

    public static List<String> oreNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String name : OreDictionary.getOreNames()) {
            if (name.startsWith("ore") && containsBlock(name)) {
                for (ItemStack stack : OreDictionary.getOres(name, false)) {
                    String target = targetId(name, stack);
                    if (target != null) {
                        names.add(target);
                    }
                }
            }
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String::compareToIgnoreCase);
        return sorted;
    }

    public static boolean matchesAny(IBlockState state, Set<String> selectedOres) {
        return matcher(selectedOres).matches(state);
    }

    public static Matcher matcher(Set<String> selectedOres) {
        if (selectedOres == null || selectedOres.isEmpty()) {
            return Matcher.EMPTY;
        }

        Map<Block, MetaMatcher> blocks = new HashMap<>();
        for (String targetName : selectedOres) {
            if (targetName == null) {
                continue;
            }
            Target target = parseTarget(targetName);
            if (target != null) {
                MetaMatcher metaMatcher = blocks.computeIfAbsent(target.block, ignored -> new MetaMatcher());
                if (target.meta == OreDictionary.WILDCARD_VALUE) {
                    metaMatcher.matchAll(targetName);
                } else {
                    metaMatcher.match(target.meta, targetName);
                }
                continue;
            }

            String oreName = oreName(targetName);
            if (!oreName.startsWith("ore")) {
                continue;
            }
            for (ItemStack stack : OreDictionary.getOres(oreName, false)) {
                if (stack.isEmpty()) {
                    continue;
                }

                Block block = Block.getBlockFromItem(stack.getItem());
                if (block == Blocks.AIR) {
                    continue;
                }

                MetaMatcher metaMatcher = blocks.computeIfAbsent(block, ignored -> new MetaMatcher());
                int meta = stack.getMetadata();
                if (meta == OreDictionary.WILDCARD_VALUE) {
                    metaMatcher.matchAll(targetName);
                } else {
                    metaMatcher.match(meta, targetName);
                }
                matchActualBlockStates(oreName, targetName, stack.getItem(), block, metaMatcher);
            }
        }
        return blocks.isEmpty() ? Matcher.EMPTY : new Matcher(blocks);
    }

    public static boolean isValidTarget(String targetName) {
        return !expandTarget(targetName).isEmpty();
    }

    public static Set<String> targetsForState(IBlockState state) {
        Set<String> targets = new LinkedHashSet<>();
        if (state == null) {
            return targets;
        }

        Block block = state.getBlock();
        int stateMeta = block.getMetaFromState(state);
        for (String oreName : OreDictionary.getOreNames()) {
            if (!oreName.startsWith("ore")) {
                continue;
            }
            for (ItemStack stack : OreDictionary.getOres(oreName, false)) {
                if (stack.isEmpty() || Block.getBlockFromItem(stack.getItem()) != block) {
                    continue;
                }

                String target = targetId(oreName, stack);
                int meta = stack.getMetadata();
                if (target != null && (meta == OreDictionary.WILDCARD_VALUE || meta == stateMeta
                        || hasOreName(new ItemStack(stack.getItem(), 1, stateMeta), oreName))) {
                    targets.add(target);
                }
            }
        }
        return targets;
    }

    public static Set<String> expandTarget(String targetName) {
        Set<String> targets = new LinkedHashSet<>();
        if (targetName == null) {
            return targets;
        }
        Target target = parseTarget(targetName);
        if (target != null) {
            if (target.oreName.startsWith("ore") && targetExists(targetName, target)) {
                targets.add(targetName);
            }
            return targets;
        }
        if (targetName.contains(TARGET_SEPARATOR)) {
            return targets;
        }
        if (!targetName.startsWith("ore")) {
            return targets;
        }
        for (ItemStack stack : OreDictionary.getOres(targetName, false)) {
            String expanded = targetId(targetName, stack);
            if (expanded != null) {
                targets.add(expanded);
            }
        }
        return targets;
    }

    public static String oreName(String targetName) {
        if (targetName == null) {
            return "";
        }
        int separator = targetName.indexOf(TARGET_SEPARATOR);
        return separator < 0 ? targetName : targetName.substring(0, separator);
    }

    public static IBlockState state(String targetName) {
        Target target = parseTarget(targetName);
        if (target == null) {
            return null;
        }

        int meta = target.meta == OreDictionary.WILDCARD_VALUE ? 0 : target.meta;
        try {
            return target.block.getStateFromMeta(meta);
        } catch (RuntimeException ignored) {
            return target.block.getDefaultState();
        }
    }

    private static void matchActualBlockStates(String oreName, String targetName, Item item, Block block, MetaMatcher metaMatcher) {
        for (IBlockState state : block.getBlockState().getValidStates()) {
            int meta = block.getMetaFromState(state);
            if (hasOreName(new ItemStack(item, 1, meta), oreName)) {
                metaMatcher.match(meta, targetName);
            }
        }
    }

    private static boolean hasOreName(ItemStack stack, String oreName) {
        if (stack.isEmpty()) {
            return false;
        }
        for (int oreId : OreDictionary.getOreIDs(stack)) {
            if (oreName.equals(OreDictionary.getOreName(oreId))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBlock(String oreName) {
        for (ItemStack stack : OreDictionary.getOres(oreName, false)) {
            if (!stack.isEmpty() && Block.getBlockFromItem(stack.getItem()) != net.minecraft.init.Blocks.AIR) {
                return true;
            }
        }
        return false;
    }

    public static final class Matcher implements ProspectorMatcher {
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
            if (state == null || blocks.isEmpty()) {
                return false;
            }

            Block block = state.getBlock();
            MetaMatcher metaMatcher = blocks.get(block);
            return metaMatcher != null && metaMatcher.matches(block.getMetaFromState(state));
        }

        @Override
        public Set<String> matchingNames(IBlockState state) {
            if (state == null || blocks.isEmpty()) {
                return Collections.emptySet();
            }

            Block block = state.getBlock();
            MetaMatcher metaMatcher = blocks.get(block);
            return metaMatcher == null ? Collections.emptySet() : metaMatcher.matchingOreNames(block.getMetaFromState(state));
        }

        public Set<String> matchingOreNames(IBlockState state) {
            return matchingNames(state);
        }
    }

    private static final class MetaMatcher {
        private boolean all;
        private int metas;
        private final Set<String> allOreNames = new LinkedHashSet<>();
        private final Map<Integer, Set<String>> oreNamesByMeta = new HashMap<>();

        private void matchAll(String oreName) {
            all = true;
            allOreNames.add(oreName);
        }

        private void match(int meta, String oreName) {
            if (meta >= 0 && meta < Integer.SIZE) {
                metas |= 1 << meta;
                oreNamesByMeta.computeIfAbsent(meta, ignored -> new LinkedHashSet<>()).add(oreName);
            }
        }

        private boolean matches(int meta) {
            return all || meta >= 0 && meta < Integer.SIZE && (metas & 1 << meta) != 0;
        }

        private Set<String> matchingOreNames(int meta) {
            if (!matches(meta)) {
                return Collections.emptySet();
            }

            Set<String> names = new LinkedHashSet<>();
            if (all) {
                names.addAll(allOreNames);
            }
            Set<String> metaNames = oreNamesByMeta.get(meta);
            if (metaNames != null) {
                names.addAll(metaNames);
            }
            return names;
        }
    }

    public static Map<String, ItemStack> oreIcons() {
        Map<String, ItemStack> icons = new LinkedHashMap<>();
        for (String name : oreNames()) {
            Target target = parseTarget(name);
            if (target != null) {
                Item item = Item.getItemFromBlock(target.block);
                int meta = target.meta == OreDictionary.WILDCARD_VALUE ? 0 : target.meta;
                ItemStack stack = new ItemStack(item, 1, meta);
                if (!stack.isEmpty()) {
                    icons.put(name, stack);
                }
            }
        }
        return icons;
    }

    private static String targetId(String oreName, ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        Block block = Block.getBlockFromItem(stack.getItem());
        ResourceLocation blockName = block.getRegistryName();
        if (block == Blocks.AIR || blockName == null) {
            return null;
        }
        int meta = stack.getMetadata();
        return oreName + TARGET_SEPARATOR + blockName + META_SEPARATOR
                + (meta == OreDictionary.WILDCARD_VALUE ? "*" : Integer.toString(meta));
    }

    private static Target parseTarget(String targetName) {
        int separator = targetName == null ? -1 : targetName.indexOf(TARGET_SEPARATOR);
        if (separator < 0) {
            return null;
        }
        int metaSeparator = targetName.lastIndexOf(META_SEPARATOR);
        if (metaSeparator <= separator + 1 || metaSeparator == targetName.length() - 1) {
            return null;
        }
        String oreName = targetName.substring(0, separator);
        Block block = blockByName(targetName.substring(separator + 1, metaSeparator));
        if (block == null || block == Blocks.AIR) {
            return null;
        }
        int meta;
        String metaName = targetName.substring(metaSeparator + 1);
        if ("*".equals(metaName)) {
            meta = OreDictionary.WILDCARD_VALUE;
        } else {
            try {
                meta = Integer.parseInt(metaName);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return new Target(oreName, block, meta);
    }

    private static boolean targetExists(String targetName, Target target) {
        for (ItemStack stack : OreDictionary.getOres(target.oreName, false)) {
            if (targetName.equals(targetId(target.oreName, stack))) {
                return true;
            }
        }
        return false;
    }

    private static Block blockByName(String name) {
        try {
            return ForgeRegistries.BLOCKS.getValue(new ResourceLocation(name));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static final class Target {
        private final String oreName;
        private final Block block;
        private final int meta;

        private Target(String oreName, Block block, int meta) {
            this.oreName = oreName;
            this.block = block;
            this.meta = meta;
        }
    }
}
