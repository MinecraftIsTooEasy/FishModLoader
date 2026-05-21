package net.xiaoyu233.fml.reload.utils.id;

import java.util.Set;

public class IdRangeAllocator {
	private static final int HASH_SEED = 0x9E3779B9;

    public static ModRange createRange(String modId, IdType type, int count) {
        if (count <= 0) return null;
        int start = findFree(type, count, modId);
        ModRange range = new ModRange(modId, type, start, count);
        Set<Integer> reserved = IdStorage.RESERVED.get(type);
        for (int i = 0; i < count; i++) {
            reserved.add(start + i);
        }
        return range;
    }

    private static int findFree(IdType type, int count, String modId) {
        Set<Integer> reserved = IdStorage.RESERVED.get(type);
        int hash = (modId.hashCode() ^ HASH_SEED) & 0x7FFFFFFF;
        int basePos = type.initial + (hash % (type.max - type.initial + 1 - count));
        for (int pos = basePos; pos + count <= type.max; pos++) {
            if (isRangeAvailable(pos, count, reserved)) {
                return pos;
            }
        }
        for (int pos = type.initial; pos + count <= type.max; pos++) {
            if (isRangeAvailable(pos, count, reserved)) {
                return pos;
            }
        }
        throw new RuntimeException("No free range for " + type + " with modId: " + modId);
    }
    
    private static boolean isRangeAvailable(int initial, int count, Set<Integer> reserved) {
        for (int i = 0; i < count; i++) {
            if (reserved.contains(initial + i)) {
                return false;
            }
        }
        return true;
    }
}