package net.xiaoyu233.fml.vulkan.pipeline;

import java.util.Arrays;
import java.util.function.LongConsumer;

/**
 * Open-addressing (linear probing) map from {@code long} to {@code long} without boxing, for the per-draw lookups of
 * {@link Pipelines} (pipeline by key, uniform descriptor set by buffer). Vulkan handles are never 0, so the value 0 stands for
 * "absent": {@link #get} returns 0 for a missing key and {@link #put} rejects 0. Any key (including 0) is allowed.
 * Not thread safe (render thread only).
 */
final class LongLongMap {
    private long[] keys = new long[32];
    private long[] values = new long[32];       // 0 = free slot
    private int size;

    int size() {
        return size;
    }

    /** The value of {@code key}, 0 when absent. */
    long get(long key) {
        long[] k = keys;
        long[] v = values;
        int mask = k.length - 1;
        for (int i = slot(key, mask); v[i] != 0L; i = (i + 1) & mask) {
            if (k[i] == key) {
                return v[i];
            }
        }
        return 0L;
    }

    /** Inserts or replaces; {@code value} must not be 0. */
    void put(long key, long value) {
        if (value == 0L) {
            throw new IllegalArgumentException("0 is the value of an absent key");
        }
        if ((size + 1) * 2 > keys.length) {
            resize(keys.length * 2);
        }
        insert(keys, values, key, value);
    }

    /** Removes the key and returns its value (0 when absent). Rebuilds the table: meant for rare removals. */
    long remove(long key) {
        long old = get(key);
        if (old == 0L) {
            return 0L;
        }
        long[] oldKeys = keys;
        long[] oldValues = values;
        keys = new long[oldKeys.length];
        values = new long[oldKeys.length];
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldValues[i] != 0L && oldKeys[i] != key) {
                insert(keys, values, oldKeys[i], oldValues[i]);
            }
        }
        return old;
    }

    void forEachValue(LongConsumer consumer) {
        long[] v = values;
        for (long value : v) {
            if (value != 0L) {
                consumer.accept(value);
            }
        }
    }

    void clear() {
        Arrays.fill(values, 0L);
        Arrays.fill(keys, 0L);
        size = 0;
    }

    private void insert(long[] k, long[] v, long key, long value) {
        int mask = k.length - 1;
        int i = slot(key, mask);
        while (v[i] != 0L) {
            if (k[i] == key) {
                v[i] = value;
                return;
            }
            i = (i + 1) & mask;
        }
        k[i] = key;
        v[i] = value;
        size++;
    }

    private void resize(int capacity) {
        long[] oldKeys = keys;
        long[] oldValues = values;
        keys = new long[capacity];
        values = new long[capacity];
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldValues[i] != 0L) {
                insert(keys, values, oldKeys[i], oldValues[i]);
            }
        }
    }

    private static int slot(long key, int mask) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> 32) & mask;
    }
}
