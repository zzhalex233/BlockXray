package com.zzhalex233.blockxray.common.util;

import net.minecraft.block.state.IBlockState;

import java.util.Set;

public interface ProspectorMatcher {
    int UNKNOWN_META = Integer.MIN_VALUE;

    boolean isEmpty();

    boolean matches(IBlockState state);

    Set<String> matchingNames(IBlockState state);

    default boolean requiresWorldMeta(IBlockState state) {
        return false;
    }

    default boolean matches(IBlockState state, int meta) {
        return matches(state);
    }

    default Set<String> matchingNames(IBlockState state, int meta) {
        return matchingNames(state);
    }
}
