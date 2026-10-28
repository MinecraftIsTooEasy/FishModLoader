package net.xiaoyu233.fml.vulkan.render.terrain;

import net.minecraft.WorldRenderer;

/** The ring grid of section handles (2.1): geometry shared by every NR-09 class. Written only by the render thread (loadRenderers). */
public final class TerrainGrid {
    static WorldRenderer[] grid = new WorldRenderer[0];
    static int wide, tall, deep;

    private TerrainGrid() {
    }

    static void set(WorldRenderer[] g, int w, int t, int d) {
        grid = g;
        wide = w;
        tall = t;
        deep = d;
    }

    public static WorldRenderer[] grid() { return grid; }
    public static int wide() { return wide; }
    public static int tall() { return tall; }
    public static int deep() { return deep; }

    /** MITE index formula {@code (z * tall + y) * wide + x} of a ring cell. */
    public static int index(int ix, int iy, int iz) {
        return (iz * tall + iy) * wide + ix;
    }

    /** O(1) lookup (CUL-02): the renderer whose position contains the block, or null (y outside 0..255). */
    public static WorldRenderer rendererFor(int x, int y, int z) {
        if (wide == 0 || y < 0 || (y >> 4) >= tall) {
            return null;
        }
        int ix = Math.floorMod(x >> 4, wide);
        int iz = Math.floorMod(z >> 4, deep);
        WorldRenderer r = grid[index(ix, y >> 4, iz)];
        return r != null && r.isRenderingCoords(x, y, z) ? r : null;
    }

    /** Ring index of the renderer, or -1 (used for {@code Entity.index_of_last_applicable_world_renderer}). */
    public static int indexFor(int x, int y, int z) {
        if (wide == 0 || y < 0 || (y >> 4) >= tall) {
            return -1;
        }
        return index(Math.floorMod(x >> 4, wide), y >> 4, Math.floorMod(z >> 4, deep));
    }

    /** Native section of a renderer (lazily created by the WorldRenderer mixin). */
    public static Section sectionOf(WorldRenderer r) {
        return ((SectionAccess) (Object) r).fml$section();
    }
}
