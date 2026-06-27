package com.zzhalex233.blockxray.client.render;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProspectorOverlaySetTest {
    @Test
    void retainsOnlyEntriesThatStillMatchSelectionAndRange() {
        ProspectorOverlaySet<TestEntry> entries = new ProspectorOverlaySet<>();
        long kept = ProspectorOverlaySet.key(0, 64, 0);
        long canceled = ProspectorOverlaySet.key(1, 64, 0);
        long outside = ProspectorOverlaySet.key(20, 64, 0);

        entries.put(new TestEntry(0, 64, 0, names("oreIron")));
        entries.put(new TestEntry(1, 64, 0, names("oreGold")));
        entries.put(new TestEntry(20, 64, 0, names("oreIron")));

        entries.retainSelected(Collections.singleton("oreIron"), ignored -> { });
        entries.pruneOutsideChunks(0, 0, 0, ignored -> { });

        assertTrue(entries.get(0, 64, 0) != null);
        assertFalse(entries.get(1, 64, 0) != null);
        assertFalse(entries.get(20, 64, 0) != null);
    }

    @Test
    void keepsAllEntriesWithinChunkRange() {
        ProspectorOverlaySet<TestEntry> entries = new ProspectorOverlaySet<>();

        entries.put(new TestEntry(0, 64, 0, names("oreIron")));
        entries.put(new TestEntry(15, 64, 15, names("oreIron")));
        entries.put(new TestEntry(16, 64, 0, names("oreIron")));

        entries.pruneOutsideChunks(0, 0, 1, ignored -> { });

        assertTrue(entries.get(0, 64, 0) != null);
        assertTrue(entries.get(15, 64, 15) != null);
        assertTrue(entries.get(16, 64, 0) != null);
    }

    @Test
    void removesEntriesOutsideChunkRange() {
        ProspectorOverlaySet<TestEntry> entries = new ProspectorOverlaySet<>();

        entries.put(new TestEntry(0, 64, 0, names("oreIron")));
        entries.put(new TestEntry(32, 64, 0, names("oreIron")));

        entries.pruneOutsideChunks(0, 0, 1, ignored -> { });

        assertTrue(entries.get(0, 64, 0) != null);
        assertFalse(entries.get(32, 64, 0) != null);
    }

    private static Set<String> names(String... names) {
        return new LinkedHashSet<>(Arrays.asList(names));
    }

    private static final class TestEntry implements ProspectorOverlaySet.Entry {
        private final int x;
        private final int y;
        private final int z;
        private final Set<String> oreNames;

        private TestEntry(int x, int y, int z, Set<String> oreNames) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.oreNames = oreNames;
        }

        @Override
        public int x() {
            return x;
        }

        @Override
        public int y() {
            return y;
        }

        @Override
        public int z() {
            return z;
        }

        @Override
        public Set<String> oreNames() {
            return oreNames;
        }
    }
}
