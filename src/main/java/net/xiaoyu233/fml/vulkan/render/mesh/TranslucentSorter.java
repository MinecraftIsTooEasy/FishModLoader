package net.xiaoyu233.fml.vulkan.render.mesh;

import java.nio.ByteBuffer;

/** Back-to-front quad sort of the translucent layer (2.7). Owner NR-10 (subtask vis). */
public final class TranslucentSorter {
    private static final int QUAD_BYTES = 112;
    private static final int VERTEX_BYTES = 28;

    private TranslucentSorter() {
    }

    /**
     * Sorts the 112-byte TERRAIN quads (4 x 28 B) of {@code quads} (count quads, position 0) in place, farthest first, by squared distance from
     * (camX,camY,camZ) given in section space to the quad centroid; stable. Returns centroids (x,y,z per quad, new order) or null when count == 0.
     */
    public static float[] sort(ByteBuffer quads, int count, float camX, float camY, float camZ) {
        if (count <= 0) {
            return null;
        }
        float[] cent = new float[3 * count];
        float[] key = new float[count];
        for (int q = 0; q < count; q++) {
            int base = q * QUAD_BYTES;
            float sx = 0, sy = 0, sz = 0;
            for (int v = 0; v < 4; v++) {
                int o = base + v * VERTEX_BYTES;
                sx += quads.getFloat(o);
                sy += quads.getFloat(o + 4);
                sz += quads.getFloat(o + 8);
            }
            float cx = sx * 0.25f, cy = sy * 0.25f, cz = sz * 0.25f;
            cent[3 * q] = cx;
            cent[3 * q + 1] = cy;
            cent[3 * q + 2] = cz;
            float dx = cx - camX, dy = cy - camY, dz = cz - camZ;
            key[q] = dx * dx + dy * dy + dz * dz;
        }
        int[] idx = new int[count];
        sortIndices(key, idx, count);
        float[] out = new float[3 * count];
        reorder(quads, count, cent, idx, out);
        return out;
    }

    /** Fills {@code idx[0..count)} with the stable descending order of {@code key} (bottom-up merge sort, no boxing). */
    public static void sortIndices(float[] key, int[] idx, int count) {
        int[] a = idx;
        int[] b = new int[count];
        for (int i = 0; i < count; i++) {
            a[i] = i;
        }
        for (int w = 1; w < count; w <<= 1) {
            for (int lo = 0; lo < count; lo += 2 * w) {
                int mid = Math.min(lo + w, count), hi = Math.min(lo + 2 * w, count);
                int i = lo, j = mid, k = lo;
                while (i < mid && j < hi) {
                    b[k++] = key[a[i]] >= key[a[j]] ? a[i++] : a[j++];
                }
                while (i < mid) b[k++] = a[i++];
                while (j < hi) b[k++] = a[j++];
            }
            int[] t = a;
            a = b;
            b = t;
        }
        if (a != idx) {
            System.arraycopy(a, 0, idx, 0, count);
        }
    }

    /**
     * Reorders {@code quads} in place so that new quad i is old quad {@code order[i]} (uses a scratch copy) and writes the matching centroids from
     * {@code oldCentroids} (old order, 3 floats per quad) into {@code outCentroids}.
     */
    public static void reorder(ByteBuffer quads, int count, float[] oldCentroids, int[] order, float[] outCentroids) {
        byte[] copy = new byte[count * QUAD_BYTES];
        ByteBuffer src = quads.duplicate();
        src.clear().position(0);
        src.get(copy, 0, copy.length);
        ByteBuffer dst = quads.duplicate();
        dst.clear();
        for (int i = 0; i < count; i++) {
            int o = order[i];
            dst.position(i * QUAD_BYTES);
            dst.put(copy, o * QUAD_BYTES, QUAD_BYTES);
            outCentroids[3 * i] = oldCentroids[3 * o];
            outCentroids[3 * i + 1] = oldCentroids[3 * o + 1];
            outCentroids[3 * i + 2] = oldCentroids[3 * o + 2];
        }
    }
}
