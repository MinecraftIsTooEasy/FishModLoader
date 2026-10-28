package net.xiaoyu233.fml.vulkan.render.terrain;

import net.minecraft.AxisAlignedBB;
import net.minecraft.Block;
import net.minecraft.ICamera;
import net.minecraft.WorldRenderer;
import net.xiaoyu233.fml.vulkan.render.frame.FrameStats;

/**
 * Visibility BFS + frustum culling with hierarchical pre-cull (2.5). Render thread only, no allocation per frame after
 * the first call (arrays are re-sized only when the ring grid object changes).
 *
 * <p>Counters: {@code visible + frustumCulled + bfsCulled + empty == total}. {@code visible(i)} lists the visited
 * sections that may produce geometry; visited sections that are built and have no quads ("empty") are traversed and
 * marked visited ({@code frameVisited}) but not listed.
 */
public final class VisibilityBfs {
    private static final boolean CAVE_CULLING = !"false".equalsIgnoreCase(System.getProperty("fml.vulkan.caveCulling", "true"));
    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};
    private static final byte OUTSIDE = 1, INSIDE = 2, PARTIAL = 3;

    private static WorldRenderer[] boundGrid;
    private static int gridWide, gridTall, gridDeep;
    private static Section[] queue = new Section[0];
    private static int[] queueIdx = new int[0];
    private static byte[] queueEntry = new byte[0];
    private static int[] seen = new int[0];
    private static Section[] vis = new Section[0];
    private static double[] sortKey = new double[0];
    private static int[] sortOrder = new int[0];
    private static Section[] sortTmp = new Section[0];

    private static int bw, bt, bd;
    private static int[] blkStamp = new int[0];
    private static long[] blkKey = new long[0];
    private static byte[] blkState = new byte[0];

    private static final AxisAlignedBB scratch = new AxisAlignedBB();
    private static int runId;
    private static int curFrame;
    private static boolean valid;
    private static int visCount, fCount, oCount, eCount, totalCount;
    private static int gridTotal, statsRun = -1;
    /** Renderers whose isInFrustum was set true by the last run (cleared at the next run; no grid scan). */
    private static WorldRenderer[] inSet = new WorldRenderer[0];
    private static int inSetN;
    private static ICamera cam;

    private VisibilityBfs() {
    }

    /** Runs the BFS for this frame: fills the visible list in visit order, sets {@code isInFrustum} (pure frustum result), {@code Section.frameVisited/bfsIndex}, counters. */
    static void run(ICamera frustum, double viewerX, double viewerY, double viewerZ, int frame) {
        WorldRenderer[] grid = TerrainGrid.grid();
        int w = TerrainGrid.wide(), t = TerrainGrid.tall(), d = TerrainGrid.deep();
        int n = grid.length;
        if (n == 0 || w <= 0 || t <= 0 || d <= 0 || n < w * t * d) {
            reset();
            return;
        }
        ensure(grid, w, t, d);
        runId++;
        curFrame = frame;
        cam = frustum;
        int qn;
        if (CAVE_CULLING) {
            qn = bfs(grid, viewerX, viewerY, viewerZ);
        } else {
            qn = frustumOnly(grid, viewerX, viewerY, viewerZ);
        }
        // finalize: mark visited, build visible list, counters
        int vn = 0, en = 0;
        for (int i = 0; i < qn; i++) {
            Section s = queue[i];
            s.frameVisited = frame;
            s.bfsIndex = i;
            if (isEmpty(s)) {
                en++;
            } else {
                vis[vn++] = s;
            }
        }
        // isInFrustum (read by the scheduler for visited sections): clear what the previous run set, then set it for the
        // visited sections that are inside the frustum. O(visited) per frame; the grid is not scanned.
        for (int i = 0; i < inSetN; i++) {
            inSet[i].isInFrustum = false;
        }
        int sn = 0;
        for (int i = 0; i < qn; i++) {
            WorldRenderer r = queue[i].owner;
            if (inFrustum(r)) {
                r.isInFrustum = true;
                inSet[sn++] = r;
            }
        }
        inSetN = sn;
        visCount = vn;
        eCount = en;
        totalCount = gridTotal;
        valid = true;
        // cam stays set until the next run: frustumCulled()/bfsCulled() classify the grid lazily from it.
        FrameStats.noteSections(vn);
    }

    private static boolean isEmpty(Section s) {
        return s.state == Section.READY && s.quads[0] == 0 && s.quads[1] == 0 && s.quads[2] == 0;
    }

    // ---------------------------------------------------------------------------------------------------------
    // traversal
    // ---------------------------------------------------------------------------------------------------------

    private static int enqueue(int qn, int idx, Section s, int dirs, int entry) {
        seen[idx] = runId;
        queue[qn] = s;
        queueIdx[qn] = idx;
        queueEntry[qn] = (byte) entry;
        s.bfsDirections = dirs;
        return qn + 1;
    }

    private static int bfs(WorldRenderer[] grid, double vx, double vy, double vz) {
        int w = gridWide, t = gridTall, d = gridDeep;
        int qn = 0;
        int cx = (int) Math.floor(vx), cy = (int) Math.floor(vy), cz = (int) Math.floor(vz);
        WorldRenderer start = TerrainGrid.rendererFor(cx, cy, cz);
        boolean allLayer = start == null;
        if (start != null) {
            allLayer = cellOpaque(start.worldObj, cx, cy, cz);
        }
        if (!allLayer) {
            int idx = TerrainGrid.indexFor(cx, cy, cz);
            qn = enqueue(qn, idx, TerrainGrid.sectionOf(start), 0, -1);
        } else {
            int iy = cy < 0 ? 0 : Math.min(t - 1, cy >> 4);
            for (int iz = 0; iz < d; iz++) {
                for (int ix = 0; ix < w; ix++) {
                    int idx = TerrainGrid.index(ix, iy, iz);
                    WorldRenderer r = grid[idx];
                    if (r != null && seen[idx] != runId && inFrustum(r)) {
                        qn = enqueue(qn, idx, TerrainGrid.sectionOf(r), 0, -1);
                    }
                }
            }
        }
        int plane = w * t;
        for (int h = 0; h < qn; h++) {
            Section cur = queue[h];
            int ci = queueIdx[h];
            int entry = queueEntry[h];
            int dirs = cur.bfsDirections;
            long conn = cur.state == Section.READY ? cur.visibility : Section.FULLY_CONNECTED;
            WorldRenderer cr = cur.owner;
            int ix = ci % w;
            int iy = (ci / w) % t;
            int iz = ci / plane;
            for (int f = 0; f < 6; f++) {
                if ((dirs & (1 << (f ^ 1))) != 0) {
                    continue;
                }
                if (entry >= 0 && (conn & ((1L << (entry * 6 + f)) | (1L << (f * 6 + entry)))) == 0L) {
                    continue;
                }
                int nx = ix + DX[f], ny = iy + DY[f], nz = iz + DZ[f];
                if (ny < 0 || ny >= t) {
                    continue;
                }
                if (nx < 0) nx = w - 1; else if (nx >= w) nx = 0;
                if (nz < 0) nz = d - 1; else if (nz >= d) nz = 0;
                int ni = (nz * t + ny) * w + nx;
                if (seen[ni] == runId) {
                    continue;
                }
                WorldRenderer nr = grid[ni];
                if (nr == null
                        || nr.posXPlus != cr.posXPlus + DX[f] * 16
                        || nr.posYPlus != cr.posYPlus + DY[f] * 16
                        || nr.posZPlus != cr.posZPlus + DZ[f] * 16) {
                    continue;
                }
                if (!inFrustum(nr)) {
                    continue;
                }
                qn = enqueue(qn, ni, TerrainGrid.sectionOf(nr), dirs | (1 << f), f ^ 1);
            }
        }
        return qn;
    }

    private static boolean cellOpaque(net.minecraft.World world, int x, int y, int z) {
        if (world == null || y < 0 || y > 255) {
            return false;
        }
        int id = world.getBlockId(x, y, z);
        return id > 0 && id < Block.opaqueCubeLookup.length && Block.opaqueCubeLookup[id];
    }

    private static int frustumOnly(WorldRenderer[] grid, double vx, double vy, double vz) {
        int qn = 0;
        for (int i = 0; i < grid.length; i++) {
            WorldRenderer r = grid[i];
            if (r != null && inFrustum(r)) {
                Section s = TerrainGrid.sectionOf(r);
                seen[i] = runId;
                queue[qn] = s;
                queueIdx[qn] = i;
                double dx = r.posXPlus - vx, dy = r.posYPlus - vy, dz = r.posZPlus - vz;
                sortKey[qn] = dx * dx + dy * dy + dz * dz;
                sortOrder[qn] = qn;
                qn++;
            }
        }
        // heapsort of sortOrder by (key, position) -- allocation free
        for (int i = qn / 2 - 1; i >= 0; i--) {
            siftDown(i, qn);
        }
        for (int end = qn - 1; end > 0; end--) {
            int tmp = sortOrder[0];
            sortOrder[0] = sortOrder[end];
            sortOrder[end] = tmp;
            siftDown(0, end);
        }
        for (int i = 0; i < qn; i++) {
            sortTmp[i] = queue[sortOrder[i]];
        }
        System.arraycopy(sortTmp, 0, queue, 0, qn);
        java.util.Arrays.fill(sortTmp, 0, qn, null);
        return qn;
    }

    private static boolean greater(int a, int b) {
        double ka = sortKey[a], kb = sortKey[b];
        return ka > kb || (ka == kb && a > b);
    }

    private static void siftDown(int i, int n) {
        while (true) {
            int l = 2 * i + 1;
            if (l >= n) {
                return;
            }
            int m = l;
            if (l + 1 < n && greater(sortOrder[l + 1], sortOrder[l])) {
                m = l + 1;
            }
            if (!greater(sortOrder[m], sortOrder[i])) {
                return;
            }
            int tmp = sortOrder[i];
            sortOrder[i] = sortOrder[m];
            sortOrder[m] = tmp;
            i = m;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // hierarchical frustum test (4x4x4 section blocks, classified lazily once per run)
    // ---------------------------------------------------------------------------------------------------------

    private static boolean inFrustum(WorldRenderer r) {
        int bx = ((r.posXPlus - 8) >> 4) >> 2;
        int by = ((r.posYPlus - 8) >> 4) >> 2;
        int bz = ((r.posZPlus - 8) >> 4) >> 2;
        int bi = (Math.floorMod(bx, bw) * bt + Math.min(Math.max(by, 0), bt - 1)) * bd + Math.floorMod(bz, bd);
        long key = ((long) bx & 0x1FFFFFL) | (((long) by & 0x3FFL) << 21) | (((long) bz & 0x1FFFFFL) << 31);
        byte st;
        if (blkStamp[bi] == runId && blkKey[bi] == key) {
            st = blkState[bi];
        } else {
            st = classify(bx, by, bz);
            blkStamp[bi] = runId;
            blkKey[bi] = key;
            blkState[bi] = st;
        }
        if (st == OUTSIDE) {
            return false;
        }
        if (st == INSIDE) {
            return true;
        }
        return cam.isBoundingBoxInFrustum(r.rendererBoundingBox);
    }

    private static byte classify(int bx, int by, int bz) {
        double x0 = bx * 64.0 - 6.0, y0 = by * 64.0 - 6.0, z0 = bz * 64.0 - 6.0;
        double x1 = bx * 64.0 + 64.0 + 6.0, y1 = by * 64.0 + 64.0 + 6.0, z1 = bz * 64.0 + 64.0 + 6.0;
        if (!cam.isBoundingBoxInFrustum(scratch.setBounds(x0, y0, z0, x1, y1, z1))) {
            return OUTSIDE;
        }
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? x0 : x1, y = (i & 2) == 0 ? y0 : y1, z = (i & 4) == 0 ? z0 : z1;
            if (!cam.isBoundingBoxInFrustum(scratch.setBounds(x, y, z, x, y, z))) {
                return PARTIAL;
            }
        }
        return INSIDE;
    }

    private static void ensure(WorldRenderer[] grid, int w, int t, int d) {
        if (grid == boundGrid && w == gridWide && t == gridTall && d == gridDeep && queue.length == grid.length) {
            return;
        }
        boundGrid = grid;
        gridWide = w;
        gridTall = t;
        gridDeep = d;
        int n = grid.length;
        queue = new Section[n];
        queueIdx = new int[n];
        queueEntry = new byte[n];
        seen = new int[n];
        vis = new Section[n];
        sortKey = new double[n];
        sortOrder = new int[n];
        sortTmp = new Section[n];
        bw = w / 4 + 2;
        bd = d / 4 + 2;
        bt = (t + 3) / 4 + 1;
        int bn = bw * bt * bd;
        blkStamp = new int[bn];
        blkKey = new long[bn];
        blkState = new byte[bn];
        inSet = new WorldRenderer[n];
        inSetN = 0;
        // New grid: the renderers start with isInFrustum=true (mixin init); count once and clear, so no run scans again.
        int total = 0;
        for (WorldRenderer r : grid) {
            if (r != null) {
                total++;
                r.isInFrustum = false;
            }
        }
        gridTotal = total;
        runId = 0;
        statsRun = -1;
    }

    /**
     * Frustum-culled and BFS-culled counts need a grid scan, so they are computed on demand, at most once per run
     * (debug screen / stats command), not in every frame.
     */
    private static void ensureStats() {
        if (!valid || cam == null || statsRun == runId) {
            return;
        }
        WorldRenderer[] grid = boundGrid;
        int f = 0;
        for (int i = 0; i < grid.length; i++) {
            WorldRenderer r = grid[i];
            if (r != null && seen[i] != runId && !inFrustum(r)) {
                f++;
            }
        }
        fCount = f;
        oCount = totalCount - visCount - eCount - f;
        statsRun = runId;
    }

    // ---------------------------------------------------------------------------------------------------------
    // queries
    // ---------------------------------------------------------------------------------------------------------

    static int visibleCount() { return visCount; }

    /** i-th visited section in BFS (front-to-back) order. */
    static Section visible(int i) { return vis[i]; }

    /** True when the section of the block was visited by this frame's BFS (ENT-04/05); true when culling data is unavailable. */
    static boolean isBlockVisible(int x, int y, int z) {
        if (!valid) {
            return true;
        }
        WorldRenderer r = TerrainGrid.rendererFor(x, y, z);
        if (r == null) {
            return false;
        }
        return TerrainGrid.sectionOf(r).frameVisited == curFrame;
    }

    static int frustumCulled() { ensureStats(); return fCount; }
    static int bfsCulled() { ensureStats(); return oCount; }
    static int emptyCount() { return eCount; }
    static int totalCount() { return totalCount; }

    static void reset() {
        valid = false;
        visCount = fCount = oCount = eCount = totalCount = 0;
        for (int i = 0; i < inSetN; i++) {
            inSet[i].isInFrustum = false;
        }
        inSetN = 0;
        cam = null;
        java.util.Arrays.fill(vis, null);
        java.util.Arrays.fill(queue, null);
    }
}
