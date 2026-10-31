package net.xiaoyu233.fml.vulkan.render.gpu;

/**
 * Two-level segregated fit allocator over a linear range of {@code totalUnits} units (one {@link GpuArena} page). O(1) allocate and
 * free, immediate coalescing of physical neighbours, block metadata in growable {@code int} arrays (no object per block, nothing is
 * allocated once the arrays have reached their working size). A block is identified by a handle (its index); handles stay valid until
 * the block is freed. Not thread safe.
 */
public final class Tlsf {
    private static final int SL_LOG = 5;
    private static final int SL_COUNT = 1 << SL_LOG;
    private static final int FL_COUNT = 32;
    private static final int NIL = -1;

    private final int totalUnits;
    private int[] offset;
    private int[] size;
    private int[] physPrev;
    private int[] physNext;
    private int[] freePrev;
    private int[] freeNext;
    private boolean[] isFree;
    private int blockCapacity;
    private int blockTop;            // number of block indices ever handed out
    private int recycled = NIL;      // head of the list of recycled block indices (chained through freeNext)
    private final int[] heads = new int[FL_COUNT * SL_COUNT];
    private final int[] slBitmap = new int[FL_COUNT];
    private int flBitmap;
    private int usedUnits;
    private int freeBlocks;

    public Tlsf(int totalUnits) {
        if (totalUnits <= 0) {
            throw new IllegalArgumentException("totalUnits must be positive: " + totalUnits);
        }
        this.totalUnits = totalUnits;
        blockCapacity = 64;
        offset = new int[blockCapacity];
        size = new int[blockCapacity];
        physPrev = new int[blockCapacity];
        physNext = new int[blockCapacity];
        freePrev = new int[blockCapacity];
        freeNext = new int[blockCapacity];
        isFree = new boolean[blockCapacity];
        java.util.Arrays.fill(heads, NIL);
        int first = newBlock();            // index 0: the block at offset 0 stays the physical head for ever
        offset[first] = 0;
        size[first] = totalUnits;
        physPrev[first] = NIL;
        physNext[first] = NIL;
        insertFree(first);
    }

    public int totalUnits() {
        return totalUnits;
    }

    public int usedUnits() {
        return usedUnits;
    }

    public int freeUnits() {
        return totalUnits - usedUnits;
    }

    /** Number of free blocks (1 = unfragmented). */
    public int freeBlockCount() {
        return freeBlocks;
    }

    /** Offset in units of a live block. */
    public int offset(int block) {
        return offset[block];
    }

    /** Size in units of a live block. */
    public int size(int block) {
        return size[block];
    }

    /** Allocates {@code units} (at least 1); returns the block handle or -1 when no free block is large enough. */
    public int allocate(int units) {
        if (units < 1) {
            units = 1;
        }
        if (units > totalUnits - usedUnits) {
            return NIL;
        }
        int block = findSuitable(units);
        if (block == NIL) {
            return NIL;
        }
        removeFree(block);
        int remainder = size[block] - units;
        if (remainder > 0) {
            int rest = newBlock();
            offset[rest] = offset[block] + units;
            size[rest] = remainder;
            int next = physNext[block];
            physPrev[rest] = block;
            physNext[rest] = next;
            if (next != NIL) {
                physPrev[next] = rest;
            }
            physNext[block] = rest;
            size[block] = units;
            insertFree(rest);
        }
        usedUnits += units;
        return block;
    }

    /** Frees a live block and merges it with free physical neighbours. */
    public void free(int block) {
        if (block < 0 || block >= blockTop || isFree[block]) {
            throw new IllegalArgumentException("block " + block + " is not a live allocation");
        }
        usedUnits -= size[block];
        int prev = physPrev[block];
        if (prev != NIL && isFree[prev]) {
            removeFree(prev);
            size[prev] += size[block];
            int next = physNext[block];
            physNext[prev] = next;
            if (next != NIL) {
                physPrev[next] = prev;
            }
            recycle(block);
            block = prev;
        }
        int next = physNext[block];
        if (next != NIL && isFree[next]) {
            removeFree(next);
            size[block] += size[next];
            int after = physNext[next];
            physNext[block] = after;
            if (after != NIL) {
                physPrev[after] = block;
            }
            recycle(next);
        }
        insertFree(block);
    }

    /** Units of the largest free block (0 when full). */
    public int largestFree() {
        if (flBitmap == 0) {
            return 0;
        }
        int fl = 31 - Integer.numberOfLeadingZeros(flBitmap);
        int sl = 31 - Integer.numberOfLeadingZeros(slBitmap[fl]);
        int best = 0;
        for (int b = heads[fl * SL_COUNT + sl]; b != NIL; b = freeNext[b]) {
            best = Math.max(best, size[b]);
        }
        return best;
    }

    /**
     * Checks every structural invariant (physical chain contiguous and complete, no two adjacent free blocks, free lists consistent with
     * the block flags and size classes, accounting); returns null when sound, else a description of the first violation. O(blocks).
     */
    public String validate() {
        int expected = 0;
        int used = 0;
        int freeCount = 0;
        int previous = NIL;
        boolean previousFree = false;
        int steps = 0;
        for (int b = 0; b != NIL; b = physNext[b]) {
            if (++steps > blockTop) {
                return "physical chain loops";
            }
            if (physPrev[b] != previous) {
                return "block " + b + " has physPrev " + physPrev[b] + ", expected " + previous;
            }
            if (offset[b] != expected) {
                return "block " + b + " starts at " + offset[b] + ", expected " + expected;
            }
            if (size[b] <= 0) {
                return "block " + b + " has size " + size[b];
            }
            if (isFree[b]) {
                if (previousFree) {
                    return "adjacent free blocks " + previous + " and " + b;
                }
                freeCount++;
            } else {
                used += size[b];
            }
            expected += size[b];
            previous = b;
            previousFree = isFree[b];
        }
        if (expected != totalUnits) {
            return "chain covers " + expected + " of " + totalUnits + " units";
        }
        if (used != usedUnits) {
            return "usedUnits " + usedUnits + " but the chain holds " + used;
        }
        if (freeCount != freeBlocks) {
            return "freeBlocks " + freeBlocks + " but the chain holds " + freeCount;
        }
        int listed = 0;
        for (int fl = 0; fl < FL_COUNT; fl++) {
            for (int sl = 0; sl < SL_COUNT; sl++) {
                boolean bit = (slBitmap[fl] & (1 << sl)) != 0;
                int head = heads[fl * SL_COUNT + sl];
                if (bit != (head != NIL)) {
                    return "bitmap/list mismatch at " + fl + "/" + sl;
                }
                int count = 0;
                for (int b = head; b != NIL; b = freeNext[b]) {
                    if (!isFree[b]) {
                        return "used block " + b + " in a free list";
                    }
                    if (mapping(size[b]) != fl * SL_COUNT + sl) {
                        return "block " + b + " of size " + size[b] + " in the wrong list " + fl + "/" + sl;
                    }
                    if (++count > blockTop) {
                        return "free list loops";
                    }
                    listed++;
                }
            }
            if (((flBitmap >>> fl) & 1) != (slBitmap[fl] != 0 ? 1 : 0)) {
                return "first level bitmap mismatch at " + fl;
            }
        }
        if (listed != freeBlocks) {
            return "free lists hold " + listed + " blocks, expected " + freeBlocks;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Packed (first level * 32 + second level) size class of {@code units}. */
    private static int mapping(int units) {
        if (units < SL_COUNT) {
            return units;       // first level 0: one class per size
        }
        int f = 31 - Integer.numberOfLeadingZeros(units);
        int fl = f - SL_LOG + 1;
        int sl = (units >>> (f - SL_LOG)) - SL_COUNT;
        return fl * SL_COUNT + sl;
    }

    private int findSuitable(int units) {
        int rounded = units;
        if (units >= SL_COUNT) {
            long up = (long) units + (1L << (31 - Integer.numberOfLeadingZeros(units) - SL_LOG)) - 1L;
            rounded = (int) Math.min(up, Integer.MAX_VALUE);
        }
        int map = mapping(rounded);
        int fl = map / SL_COUNT;
        int sl = map % SL_COUNT;
        int slMap = slBitmap[fl] & (-1 << sl);
        if (slMap == 0) {
            int flMap = fl + 1 >= 32 ? 0 : flBitmap & (-1 << (fl + 1));
            if (flMap != 0) {
                fl = Integer.numberOfTrailingZeros(flMap);
                slMap = slBitmap[fl];
            }
        }
        if (slMap != 0) {
            return heads[fl * SL_COUNT + Integer.numberOfTrailingZeros(slMap)];
        }
        // rounding up skipped the class that holds the request itself: its blocks may still be large enough
        int own = mapping(units);
        for (int b = heads[own]; b != NIL; b = freeNext[b]) {
            if (size[b] >= units) {
                return b;
            }
        }
        return NIL;
    }

    private void insertFree(int block) {
        int map = mapping(size[block]);
        int fl = map / SL_COUNT;
        int sl = map % SL_COUNT;
        int head = heads[map];
        freePrev[block] = NIL;
        freeNext[block] = head;
        if (head != NIL) {
            freePrev[head] = block;
        }
        heads[map] = block;
        slBitmap[fl] |= 1 << sl;
        flBitmap |= 1 << fl;
        isFree[block] = true;
        freeBlocks++;
    }

    private void removeFree(int block) {
        int map = mapping(size[block]);
        int fl = map / SL_COUNT;
        int sl = map % SL_COUNT;
        int prev = freePrev[block];
        int next = freeNext[block];
        if (prev != NIL) {
            freeNext[prev] = next;
        } else {
            heads[map] = next;
        }
        if (next != NIL) {
            freePrev[next] = prev;
        }
        if (heads[map] == NIL) {
            slBitmap[fl] &= ~(1 << sl);
            if (slBitmap[fl] == 0) {
                flBitmap &= ~(1 << fl);
            }
        }
        isFree[block] = false;
        freeBlocks--;
    }

    private int newBlock() {
        if (recycled != NIL) {
            int b = recycled;
            recycled = freeNext[b];
            return b;
        }
        if (blockTop == blockCapacity) {
            blockCapacity <<= 1;
            offset = java.util.Arrays.copyOf(offset, blockCapacity);
            size = java.util.Arrays.copyOf(size, blockCapacity);
            physPrev = java.util.Arrays.copyOf(physPrev, blockCapacity);
            physNext = java.util.Arrays.copyOf(physNext, blockCapacity);
            freePrev = java.util.Arrays.copyOf(freePrev, blockCapacity);
            freeNext = java.util.Arrays.copyOf(freeNext, blockCapacity);
            isFree = java.util.Arrays.copyOf(isFree, blockCapacity);
        }
        return blockTop++;
    }

    private void recycle(int block) {
        isFree[block] = false;
        size[block] = 0;
        freeNext[block] = recycled;
        recycled = block;
    }
}
