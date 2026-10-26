package net.xiaoyu233.fml.vulkan.gl.draw;

/**
 * Allocator of GL object names (positive ints) that hands out contiguous ranges: first fit over a sorted, coalescing free list,
 * otherwise from the top. Used for display lists (glGenLists reserves ranges), buffer objects, queries and vertex arrays; there is no
 * fixed upper limit (MITE reserves 110976 list names at once and re-generates ranges every time the clouds are rebuilt).
 * Render thread only.
 */
final class IdRanges {
    /** Every name {@code >= top} is free (never handed out, or released back to the tail). */
    private int top = 1;
    /** Sorted, disjoint, non-adjacent free ranges {@code [freeStart[i], freeEnd[i])} below {@link #top}. */
    private int[] freeStart = new int[8];
    private int[] freeEnd = new int[8];
    private int freeCount;

    /** Returns the first name of {@code count} contiguous free names, or 0 when {@code count <= 0} or the name space is exhausted. */
    int allocate(int count) {
        if (count <= 0) {
            return 0;
        }
        for (int i = 0; i < freeCount; i++) {
            int len = freeEnd[i] - freeStart[i];
            if (len >= count) {
                int id = freeStart[i];
                if (len == count) {
                    removeRange(i);
                } else {
                    freeStart[i] += count;
                }
                return id;
            }
        }
        long end = (long) top + count;
        if (end > Integer.MAX_VALUE) {
            return 0;
        }
        int id = top;
        top = (int) end;
        return id;
    }

    /** Returns {@code [first, first + count)} to the pool; names that are not allocated (or already free) are ignored. */
    void free(int first, int count) {
        long lo = Math.max((long) first, 1L);
        long hi = Math.min((long) first + count, (long) top);
        if (lo >= hi) {
            return;
        }
        // first free range that overlaps or touches [lo, hi)
        int i = 0;
        while (i < freeCount && freeEnd[i] < lo) {
            i++;
        }
        int j = i;
        long newLo = lo;
        long newHi = hi;
        while (j < freeCount && freeStart[j] <= newHi) {
            newLo = Math.min(newLo, freeStart[j]);
            newHi = Math.max(newHi, freeEnd[j]);
            j++;
        }
        if (newHi == top) {
            // the merged range touches the top: lower the top instead of keeping a range
            top = (int) newLo;
            removeRanges(i, j);
            return;
        }
        if (j == i) {
            insertRange(i, (int) newLo, (int) newHi);
        } else {
            freeStart[i] = (int) newLo;
            freeEnd[i] = (int) newHi;
            removeRanges(i + 1, j);
        }
    }

    /** True when the name is currently handed out. */
    boolean isAllocated(int id) {
        if (id < 1 || id >= top) {
            return false;
        }
        int lo = 0;
        int hi = freeCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (id < freeStart[mid]) {
                hi = mid - 1;
            } else if (id >= freeEnd[mid]) {
                lo = mid + 1;
            } else {
                return false;
            }
        }
        return true;
    }

    /** Marks one specific name as allocated (names chosen by the application, e.g. glNewList / glBindBuffer on an ungenerated name). */
    void reserve(int id) {
        if (id < 1 || isAllocated(id)) {
            return;
        }
        if (id >= top) {
            int oldTop = top;
            top = id + 1;
            if (id > oldTop) {
                insertRange(freeCount, oldTop, id); // everything between the old top and the reserved name stays free
            }
            return;
        }
        for (int i = 0; i < freeCount; i++) {
            if (id >= freeStart[i] && id < freeEnd[i]) {
                int s = freeStart[i];
                int e = freeEnd[i];
                if (e - s == 1) {
                    removeRange(i);
                } else if (id == s) {
                    freeStart[i] = s + 1;
                } else if (id == e - 1) {
                    freeEnd[i] = e - 1;
                } else {
                    freeEnd[i] = id;
                    insertRange(i + 1, id + 1, e);
                }
                return;
            }
        }
    }

    void clear() {
        top = 1;
        freeCount = 0;
    }

    private void insertRange(int at, int start, int end) {
        if (freeCount == freeStart.length) {
            freeStart = java.util.Arrays.copyOf(freeStart, freeCount * 2);
            freeEnd = java.util.Arrays.copyOf(freeEnd, freeCount * 2);
        }
        System.arraycopy(freeStart, at, freeStart, at + 1, freeCount - at);
        System.arraycopy(freeEnd, at, freeEnd, at + 1, freeCount - at);
        freeStart[at] = start;
        freeEnd[at] = end;
        freeCount++;
    }

    private void removeRange(int at) {
        removeRanges(at, at + 1);
    }

    private void removeRanges(int from, int to) {
        if (to <= from) {
            return;
        }
        System.arraycopy(freeStart, to, freeStart, from, freeCount - to);
        System.arraycopy(freeEnd, to, freeEnd, from, freeCount - to);
        freeCount -= to - from;
    }
}
