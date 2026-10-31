package net.xiaoyu233.fml.vulkan.render.terrain;

/** Public read-only view of the terrain counters for the F3 overlay (the originals are package private). */
public final class TerrainStatsView {
    private TerrainStatsView() {
    }

    /** Sections visited by the last frame's visibility search (drawn candidates). */
    public static int sectionsVisible() {
        return VisibilityBfs.visibleCount();
    }

    /** Sections of the loaded grid. */
    public static int sectionsTotal() {
        return VisibilityBfs.totalCount();
    }
}
