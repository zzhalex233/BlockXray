package com.zzhalex233.blockxray.client.render;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class ProspectorOverlaySet<E extends ProspectorOverlaySet.Entry> {
    private final Map<Long, E> entries = new LinkedHashMap<>();

    void put(E entry) {
        entries.put(key(entry.x(), entry.y(), entry.z()), entry);
    }

    E get(int x, int y, int z) {
        return entries.get(key(x, y, z));
    }

    E remove(int x, int y, int z) {
        return entries.remove(key(x, y, z));
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    Collection<E> values() {
        return entries.values();
    }

    void clear() {
        entries.clear();
    }

    boolean retainSelected(Set<String> selectedOres, Consumer<E> removed) {
        boolean changed = false;
        Iterator<E> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            E entry = iterator.next();
            if (!matchesAny(entry.oreNames(), selectedOres)) {
                iterator.remove();
                removed.accept(entry);
                changed = true;
            }
        }
        return changed;
    }

    boolean pruneOutsideChunks(int centerChunkX, int centerChunkZ, int radius, Consumer<E> removed) {
        boolean changed = false;
        Iterator<E> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            E entry = iterator.next();
            if (Math.abs((entry.x() >> 4) - centerChunkX) > radius || Math.abs((entry.z() >> 4) - centerChunkZ) > radius) {
                iterator.remove();
                removed.accept(entry);
                changed = true;
            }
        }
        return changed;
    }

    boolean pruneOutsideBlocks(int centerX, int centerZ, int radius, Consumer<E> removed) {
        boolean changed = false;
        Iterator<E> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            E entry = iterator.next();
            if (Math.abs(entry.x() - centerX) > radius || Math.abs(entry.z() - centerZ) > radius) {
                iterator.remove();
                removed.accept(entry);
                changed = true;
            }
        }
        return changed;
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) (y & 0xFFF);
    }

    static long distanceSq(int ax, int ay, int az, int bx, int by, int bz) {
        long dx = ax - bx;
        long dy = ay - by;
        long dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean matchesAny(Set<String> entryOres, Set<String> selectedOres) {
        for (String ore : entryOres) {
            if (selectedOres.contains(ore)) {
                return true;
            }
        }
        return false;
    }

    interface Entry {
        int x();

        int y();

        int z();

        Set<String> oreNames();
    }
}
