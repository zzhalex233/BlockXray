package com.zzhalex233.blockxray.common.util;

import net.minecraft.block.state.IBlockState;

import java.util.Set;

public interface ProspectorMatcher {
    boolean isEmpty();

    boolean matches(IBlockState state);

    Set<String> matchingNames(IBlockState state);
}
