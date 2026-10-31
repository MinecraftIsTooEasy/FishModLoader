package net.xiaoyu233.fml.vulkan.render.terrain;

import java.nio.ByteBuffer;

/** Distance-sorted translucent section order (2.7) and resort scheduling. Render thread. NR-09 subtask S5. */
public final class TranslucentOrder {
    private static final int MAX_RESORTS_PER_FRAME = 16;
    private static final int QUAD_BYTES = 112;

    private static Section[] order = new Section[256];
    private static double[] dist = new double[256];
    private static int[] idx = new int[256];
    private static int[] tmp = new int[256];
    private static int count;

    private TranslucentOrder() {
    }

    /** Sections of this frame's visible list that have a TRANSLUCENT layer, farthest first (stable on bfsIndex); returns count. */
    static int build(double camX, double camY, double camZ) {
        int vc = VisibilityBfs.visibleCount();
        if (order.length < vc) {
            int n = Math.max(vc, order.length * 2);
            order = new Section[n];
            dist = new double[n];
            idx = new int[n];
            tmp = new int[n];
        }
        // visible list order is ascending bfsIndex by contract; stabilise anyway via explicit tie-break
        Section[] cand = order;
        int n = 0;
        for (int i = 0; i < vc; i++) {
            Section s = VisibilityBfs.visible(i);
            if (s == null || !s.hasLayer(Section.TRANSLUCENT)) {
                continue;
            }
            double dx = camX - (s.x + 8), dy = camY - (s.y + 8), dz = camZ - (s.z + 8);
            cand[n] = s;
            dist[n] = dx * dx + dy * dy + dz * dz;
            n++;
        }
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        mergeSort(idx, tmp, n, dist, cand, true);
        // permute in place via tmp-free pass: copy sections to a scratch using the dist-free approach
        Section[] scratch = scratchSections(n);
        for (int i = 0; i < n; i++) {
            scratch[i] = cand[idx[i]];
        }
        System.arraycopy(scratch, 0, cand, 0, n);
        for (int i = n; i < count; i++) {
            cand[i] = null;
        }
        for (int i = 0; i < n; i++) {
            scratch[i] = null;
        }
        count = n;
        return n;
    }

    private static Section[] scratch = new Section[256];

    private static Section[] scratchSections(int n) {
        if (scratch.length < n) {
            scratch = new Section[Math.max(n, scratch.length * 2)];
        }
        return scratch;
    }

    /** Farthest-first element i of the last build(). */
    static Section at(int i) { return order[i]; }

    /** Nearest-first view (prepass mode). */
    static Section atNearest(int i) { return order[count - 1 - i]; }

    static int count() { return count; }

    /**
     * Stable merge sort of idx[0..n) by dist descending (desc) with ties by ascending original index
     * (idx initially 0..n-1 and sorted stably, equivalent to ascending bfsIndex for an ascending visible list;
     * ties are additionally broken by bfsIndex when sections are given).
     */
    private static void mergeSort(int[] a, int[] t, int n, double[] key, Section[] secs, boolean desc) {
        for (int w = 1; w < n; w <<= 1) {
            for (int lo = 0; lo < n; lo += 2 * w) {
                int mid = Math.min(lo + w, n), hi = Math.min(lo + 2 * w, n);
                int i = lo, j = mid, k = lo;
                while (i < mid && j < hi) {
                    if (before(a[j], a[i], key, secs, desc)) {
                        t[k++] = a[j++];
                    } else {
                        t[k++] = a[i++];
                    }
                }
                while (i < mid) {
                    t[k++] = a[i++];
                }
                while (j < hi) {
                    t[k++] = a[j++];
                }
            }
            System.arraycopy(t, 0, a, 0, n);
        }
    }

    /** True when element p must come strictly before q. */
    private static boolean before(int p, int q, double[] key, Section[] secs, boolean desc) {
        double kp = key[p], kq = key[q];
        if (kp != kq) {
            return desc ? kp > kq : kp < kq;
        }
        return secs != null && secs[p].bfsIndex < secs[q].bfsIndex;
    }

    /** Schedules up to 16 resort tasks (nearest first) for sections whose camera moved >= 1 block since lastSort. */
    static void scheduleResorts(double camX, double camY, double camZ) {
        int submitted = 0;
        for (int i = 0; i < count && submitted < MAX_RESORTS_PER_FRAME; i++) {
            Section s = atNearest(i);
            // only READY sections: a section that is queued or building is rebuilt (and sorted) by its own task
            if (s.resortPending || s.state != Section.READY || s.translucentCentroids == null || s.quads[Section.TRANSLUCENT] <= 0) {
                continue;
            }
            if (!Double.isNaN(s.lastSortX)) {
                double dx = camX - s.lastSortX, dy = camY - s.lastSortY, dz = camZ - s.lastSortZ;
                if (dx * dx + dy * dy + dz * dz < 1.0) {
                    continue;
                }
            }
            submitted++;
            // Resort on the render thread: MeshWorkers.runTask drops resort-only tasks without producing a result, so an async
            // resort left RESORT_OUT / resortPending set forever and the scheduler then never rebuilt that section again
            // (stale water after block edits).
            resortSync(s, camX, camY, camZ);
        }
    }

    private static int[] syncOrder = new int[0];
    /** Render-thread scratch for sortQuadsBackToFront (grow-only; reused across resorts). */
    private static double[] sortKeys = new double[0];
    private static int[] sortTmp = new int[0];
    /** Render-thread upload scratch for resortSync (grow-only; commitResorted copies it, so it is safe to reuse). */
    private static ByteBuffer syncOut = ByteBuffer.allocateDirect(0);

    /** Synchronous fallback: sorts the stored translucent quads back-to-front and re-uploads. */
    private static void resortSync(Section s, double camX, double camY, double camZ) {
        int quads = s.quads[Section.TRANSLUCENT];
        ByteBuffer src = s.translucentVerts;
        if (src == null || s.translucentCentroids == null || s.translucentCentroids.length < quads * 3
                || src.capacity() < quads * QUAD_BYTES) {
            return;
        }
        if (syncOrder.length < quads) {
            syncOrder = new int[Math.max(quads, syncOrder.length * 2)];
        }
        float[] c = s.translucentCentroids;
        sortQuadsBackToFront(c, quads, (float) (camX - s.x), (float) (camY - s.y), (float) (camZ - s.z), syncOrder);
        int bytes = quads * QUAD_BYTES;
        if (syncOut.capacity() < bytes) {
            syncOut = ByteBuffer.allocateDirect(Math.max(bytes, syncOut.capacity() * 2));
        }
        ByteBuffer out = syncOut.order(src.order());
        out.clear();
        out.limit(bytes);
        float[] nc = new float[c.length];
        for (int q = 0; q < quads; q++) {
            int o = syncOrder[q];
            out.put(q * QUAD_BYTES, src, o * QUAD_BYTES, QUAD_BYTES);
            nc[q * 3] = c[o * 3];
            nc[q * 3 + 1] = c[o * 3 + 1];
            nc[q * 3 + 2] = c[o * 3 + 2];
        }
        if (!SectionCommit.commitResorted(s, out)) {
            // no room in the arena: keep the old allocation and the old CPU copy consistent with it, retry next frame
            return;
        }
        src.clear();
        src.put(0, out, 0, quads * QUAD_BYTES);
        s.translucentCentroids = nc;
        s.lastSortX = camX;
        s.lastSortY = camY;
        s.lastSortZ = camZ;
    }

    /** Stable back-to-front (farthest first) order of quads by squared centroid distance; outOrder[k] = source quad index. */
    static void sortQuadsBackToFront(float[] centroids, int quads, float cx, float cy, float cz, int[] outOrder) {
        if (sortKeys.length < quads) {
            sortKeys = new double[Math.max(quads, sortKeys.length * 2)];
            sortTmp = new int[sortKeys.length];
        }
        double[] d = sortKeys;
        int[] t = sortTmp;
        for (int q = 0; q < quads; q++) {
            double dx = centroids[q * 3] - cx, dy = centroids[q * 3 + 1] - cy, dz = centroids[q * 3 + 2] - cz;
            d[q] = dx * dx + dy * dy + dz * dz;
            outOrder[q] = q;
        }
        mergeSort(outOrder, t, quads, d, null, true);
    }
}
