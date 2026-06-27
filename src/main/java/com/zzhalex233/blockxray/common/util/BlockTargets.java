package com.zzhalex233.blockxray.common.util;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
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
    private BlockTargets() {
    }

    public static List<String> names() {
        List<String> names = new ArrayList<>();
        for (Block block : ForgeRegistries.BLOCKS) {
            ResourceLocation name = block.getRegistryName();
            if (name != null && block != Blocks.AIR) {
                names.add(name.toString());
            }
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    public static Map<String, ItemStack> icons() {
        Map<String, ItemStack> icons = new LinkedHashMap<>();
        for (String name : names()) {
            Block block = blockByName(name);
            if (block != null && block != Blocks.AIR) {
                ItemStack stack = new ItemStack(block);
                if (!stack.isEmpty()) {
                    icons.put(name, stack);
                }
            }
        }
        return icons;
    }

    public static ProspectorMatcher matcher(Set<String> selectedBlocks) {
        if (selectedBlocks == null || selectedBlocks.isEmpty()) {
            return Matcher.EMPTY;
        }

        Map<Block, String> blocks = new HashMap<>();
        for (String name : selectedBlocks) {
            if (name == null) {
                continue;
            }
            Block block = blockByName(name);
            if (block != null && block != Blocks.AIR) {
                blocks.put(block, name);
            }
        }
        return blocks.isEmpty() ? Matcher.EMPTY : new Matcher(blocks);
    }

    public static boolean isValidName(String name) {
        Block block = blockByName(name);
        return block != null && block != Blocks.AIR;
    }

    private static Block blockByName(String name) {
        try {
            return ForgeRegistries.BLOCKS.getValue(new ResourceLocation(name));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static final class Matcher implements ProspectorMatcher {
        private static final Matcher EMPTY = new Matcher(Collections.emptyMap());

        private final Map<Block, String> blocks;

        private Matcher(Map<Block, String> blocks) {
            this.blocks = blocks;
        }

        @Override
        public boolean isEmpty() {
            return blocks.isEmpty();
        }

        @Override
        public boolean matches(IBlockState state) {
            return state != null && blocks.containsKey(state.getBlock());
        }

        @Override
        public Set<String> matchingNames(IBlockState state) {
            if (state == null) {
                return Collections.emptySet();
            }
            String name = blocks.get(state.getBlock());
            if (name == null) {
                return Collections.emptySet();
            }
            Set<String> names = new LinkedHashSet<>();
            names.add(name);
            return names;
        }
    }
}
