package net.xiaoyu233.fml.vulkan.texture;

/**
 * Alpha-weighted 2x2 box filter for the atlas mip chains (1.11, {@code fml.vulkan.mipmaps}). Every sprite is reduced on its own, so
 * no colour bleeds across sprite borders; colour is weighted by alpha, so the black of transparent texels never darkens the edge of
 * a cutout (leaves keep their silhouette and their colour); alpha is the plain mean, which keeps cutout coverage at the alpha test.
 */
public final class AtlasMips {
    private static int[][] levels = new int[5][];

    private AtlasMips() {
    }

    /** Scratch destination for chain level {@code level} (>= 1) with room for {@code count} ints; contents are overwritten by the caller. */
    public static int[] buffer(int level, int count) {
        if (level >= levels.length) {
            levels = java.util.Arrays.copyOf(levels, level + 1);
        }
        int[] b = levels[level];
        if (b == null || b.length < count) {
            b = new int[Math.max(count, 256)];
            levels[level] = b;
        }
        return b;
    }

    /** Reduces the {@code w x h} image at {@code src[srcOff..]} (row stride {@code w}) to {@code (w/2) x (h/2)} in {@code dst}. w and h are even. */
    public static void downsample(int[] src, int srcOff, int w, int h, int[] dst) {
        int dw = w >> 1;
        int dh = h >> 1;
        for (int y = 0; y < dh; y++) {
            int row0 = srcOff + (y << 1) * w;
            int row1 = row0 + w;
            int out = y * dw;
            for (int x = 0; x < dw; x++) {
                int i0 = row0 + (x << 1);
                int i1 = row1 + (x << 1);
                int p0 = src[i0];
                int p1 = src[i0 + 1];
                int p2 = src[i1];
                int p3 = src[i1 + 1];
                int a0 = p0 >>> 24;
                int a1 = p1 >>> 24;
                int a2 = p2 >>> 24;
                int a3 = p3 >>> 24;
                int sumA = a0 + a1 + a2 + a3;
                int r;
                int g;
                int b;
                if (sumA == 0) {
                    r = (((p0 >> 16) & 0xFF) + ((p1 >> 16) & 0xFF) + ((p2 >> 16) & 0xFF) + ((p3 >> 16) & 0xFF) + 2) >> 2;
                    g = (((p0 >> 8) & 0xFF) + ((p1 >> 8) & 0xFF) + ((p2 >> 8) & 0xFF) + ((p3 >> 8) & 0xFF) + 2) >> 2;
                    b = ((p0 & 0xFF) + (p1 & 0xFF) + (p2 & 0xFF) + (p3 & 0xFF) + 2) >> 2;
                } else {
                    int half = sumA >> 1;
                    r = (((p0 >> 16) & 0xFF) * a0 + ((p1 >> 16) & 0xFF) * a1 + ((p2 >> 16) & 0xFF) * a2 + ((p3 >> 16) & 0xFF) * a3 + half) / sumA;
                    g = (((p0 >> 8) & 0xFF) * a0 + ((p1 >> 8) & 0xFF) * a1 + ((p2 >> 8) & 0xFF) * a2 + ((p3 >> 8) & 0xFF) * a3 + half) / sumA;
                    b = ((p0 & 0xFF) * a0 + (p1 & 0xFF) * a1 + (p2 & 0xFF) * a2 + (p3 & 0xFF) * a3 + half) / sumA;
                }
                int a = (sumA + 2) >> 2;
                dst[out + x] = a << 24 | r << 16 | g << 8 | b;
            }
        }
    }
}
