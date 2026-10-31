package net.xiaoyu233.fml.vulkan.render.texture;

/** Alpha test helper of the atlas opacity map. */
public final class AtlasOpacity {
    private AtlasOpacity() {
    }

    /** True when the first {@code count} ARGB ints all have alpha 255. */
    public static boolean allOpaque(int[] argb, int count) {
        int acc = -1;
        int n = Math.min(count, argb.length);
        for (int i = 0; i < n; i++) {
            acc &= argb[i];
        }
        return (acc >>> 24) == 0xFF;
    }
}
