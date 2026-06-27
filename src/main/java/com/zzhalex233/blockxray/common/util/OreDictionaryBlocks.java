package com.zzhalex233.blockxray.common.util;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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
    private OreDictionaryBlocks() {
    }

    public static List<String> oreNames() {
        List<String> names = new ArrayList<>();
        for (String name : OreDictionary.getOreNames()) {
            if (name.startsWith("ore") && containsBlock(name)) {
                names.add(name);
            }
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    public static boolean matchesAny(IBlockState state, Set<String> selectedOres) {
        return matcher(selectedOres).matches(state);
    }

    public static Matcher matcher(Set<String> selectedOres) {
        if (selectedOres == null || selectedOres.isEmpty()) {
            return Matcher.EMPTY;
        }

        Map<Block, MetaMatcher> blocks = new HashMap<>();
        for (String oreName : selectedOres) {
            if (oreName == null || !oreName.startsWith("ore")) {
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
                    metaMatcher.matchAll(oreName);
                } else {
                    metaMatcher.match(meta, oreName);
                }
                matchActualBlockStates(oreName, stack.getItem(), block, metaMatcher);
            }
        }
        return blocks.isEmpty() ? Matcher.EMPTY : new Matcher(blocks);
    }

    private static void matchActualBlockStates(String oreName, Item item, Block block, MetaMatcher metaMatcher) {
        for (IBlockState state : block.getBlockState().getValidStates()) {
            int meta = block.getMetaFromState(state);
            if (hasOreName(new ItemStack(item, 1, meta), oreName)) {
                metaMatcher.match(meta, oreName);
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
            for (ItemStack stack : OreDictionary.getOres(name, false)) {
                if (!stack.isEmpty() && Block.getBlockFromItem(stack.getItem()) != net.minecraft.init.Blocks.AIR) {
                    icons.put(name, stack.copy());
                    break;
                }
            }
        }
        return icons;
    }
}
