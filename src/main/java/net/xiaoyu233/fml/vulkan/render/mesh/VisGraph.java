package net.xiaoyu233.fml.vulkan.render.mesh;

import net.minecraft.Block;

/** Section face-to-face visibility (2.5). Worker side; owner NR-10 (subtask vis). Face bit order = Facing 0..5: DOWN, UP, NORTH, SOUTH, WEST, EAST. */
public final class VisGraph {
    /** Every ordered pair (a != b) of faces connected: unbuilt / fallback sections. */
    public static final long FULL = fullMask();
    /** Scratch length needed by the compute overloads (4096 stack ints + 128 ints of visited bits). */
    public static final int SCRATCH_SIZE = 4096 + 128;

    private VisGraph() {
    }

    private static long fullMask() {
        long m = 0;
        for (int a = 0; a < 6; a++) {
            for (int b = 0; b < 6; b++) {
                if (a != b) {
                    m |= 1L << (a * 6 + b);
                }
            }
        }
        return m;
    }

    public static int[] newScratch() {
        return new int[SCRATCH_SIZE];
    }

    public static boolean connected(long vis, int faceA, int faceB) {
        return (vis >>> (faceA * 6 + faceB) & 1L) != 0;
    }

    private static final ThreadLocal<int[]> SCRATCH = ThreadLocal.withInitial(VisGraph::newScratch);

    /** Convenience: reads the snapshot's inner 16^3 cells at its own origin (absolute world coordinates) with a per-thread scratch. */
    static long compute(SectionSnapshot snapshot) {
        return compute(snapshot, snapshot.originX(), snapshot.originY(), snapshot.originZ(), SCRATCH.get());
    }

    /** Reads the inner 16^3 cells at (originX + x, originY + y, originZ + z) through {@code snapshot.getBlockId}. */
    static long compute(SectionSnapshot snapshot, int originX, int originY, int originZ, int[] scratch) {
        final boolean[] lookup = Block.opaqueCubeLookup;
        final int[] stack = scratch;
        java.util.Arrays.fill(stack, 4096, SCRATCH_SIZE, 0);
        long vis = 0;
        for (int start = 0; start < 4096; start++) {
            if ((stack[4096 + (start >> 5)] >>> (start & 31) & 1) != 0) {
                continue;
            }
            if (lookup[snapshot.getBlockId(originX + (start & 15), originY + (start >> 8), originZ + (start >> 4 & 15)) & 4095]) {
                stack[4096 + (start >> 5)] |= 1 << (start & 31);
                continue;
            }
            vis |= fill(null, snapshot, originX, originY, originZ, stack, start);
        }
        return vis;
    }

    /** Snapshot-free overload: {@code opaque[(y << 8) | (z << 4) | x]}. */
    public static long compute(boolean[] opaque, int[] scratch) {
        final int[] stack = scratch;
        java.util.Arrays.fill(stack, 4096, SCRATCH_SIZE, 0);
        long vis = 0;
        for (int start = 0; start < 4096; start++) {
            if ((stack[4096 + (start >> 5)] >>> (start & 31) & 1) != 0) {
                continue;
            }
            if (opaque[start]) {
                stack[4096 + (start >> 5)] |= 1 << (start & 31);
                continue;
            }
            vis |= fill(opaque, null, 0, 0, 0, stack, start);
        }
        return vis;
    }

    public static long compute(boolean[] opaque) {
        return compute(opaque, newScratch());
    }

    private static boolean isOpaque(boolean[] opaque, SectionSnapshot s, int ox, int oy, int oz, int i) {
        if (opaque != null) {
            return opaque[i];
        }
        return Block.opaqueCubeLookup[s.getBlockId(ox + (i & 15), oy + (i >> 8), oz + (i >> 4 & 15)) & 4095];
    }

    /** Flood fill from {@code start} (not yet visited, not opaque); returns the pair bits of the touched faces. */
    private static long fill(boolean[] opaque, SectionSnapshot s, int ox, int oy, int oz, int[] stack, int start) {
        int sp = 0;
        stack[sp++] = start;
        stack[4096 + (start >> 5)] |= 1 << (start & 31);
        int faces = 0;
        while (sp > 0) {
            int i = stack[--sp];
            int x = i & 15, z = i >> 4 & 15, y = i >> 8;
            if (y == 0) faces |= 1;
            if (y == 15) faces |= 2;
            if (z == 0) faces |= 4;
            if (z == 15) faces |= 8;
            if (x == 0) faces |= 16;
            if (x == 15) faces |= 32;
            for (int d = 0; d < 6; d++) {
                int n;
                switch (d) {
                    case 0: if (y == 0) continue; n = i - 256; break;
                    case 1: if (y == 15) continue; n = i + 256; break;
                    case 2: if (z == 0) continue; n = i - 16; break;
                    case 3: if (z == 15) continue; n = i + 16; break;
                    case 4: if (x == 0) continue; n = i - 1; break;
                    default: if (x == 15) continue; n = i + 1; break;
                }
                int w = 4096 + (n >> 5), bit = 1 << (n & 31);
                if ((stack[w] & bit) != 0) {
                    continue;
                }
                stack[w] |= bit;
                if (!isOpaque(opaque, s, ox, oy, oz, n)) {
                    stack[sp++] = n;
                }
            }
        }
        long v = 0;
        for (int a = 0; a < 6; a++) {
            if ((faces >> a & 1) == 0) continue;
            for (int b = 0; b < 6; b++) {
                if (a != b && (faces >> b & 1) != 0) {
                    v |= 1L << (a * 6 + b);
                }
            }
        }
        return v;
    }
}
