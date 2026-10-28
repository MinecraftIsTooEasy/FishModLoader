package net.xiaoyu233.fml.vulkan.render.terrain;

import net.minecraft.EntityLivingBase;
import net.minecraft.ICamera;
import net.minecraft.WorldClient;
import net.minecraft.WorldRenderer;

/**
 * Terrain front door of the native renderer (2.2, 5.8). NR-09: the NR-00 legacy delegation to RenderGlobal is deleted; every call goes to
 * the NR-09 subsystems ({@link VisibilityBfs}, {@link SectionScheduler}, {@link SectionCommit}, {@link TerrainDraw}).
 */
public final class TerrainRenderer {
    private static float partialTicks;
    private static int frame;
    private static double camX, camY, camZ;

    private TerrainRenderer() {
    }

    public static void onLoadRenderers(WorldRenderer[] grid, int wide, int tall, int deep) {
        releaseGrid();
        TerrainGrid.set(grid, wide, tall, deep);
        SectionScheduler.reset();
        VisibilityBfs.reset();
    }

    public static void onWorldChanged(WorldClient world) {
        releaseGrid();
        SectionScheduler.reset();
        VisibilityBfs.reset();
    }

    /** Frees every section's arena allocations and native copies of the current grid (frees are deferred by the arena). */
    public static void releaseGrid() {
        WorldRenderer[] old = TerrainGrid.grid();
        if (old == null) {
            return;
        }
        for (WorldRenderer r : old) {
            if (r != null) {
                SectionCommit.release(TerrainGrid.sectionOf(r));
            }
        }
    }

    public static void setupFrame(ICamera frustum, double viewerX, double viewerY, double viewerZ, float partialTicksNow) {
        partialTicks = partialTicksNow;
        camX = viewerX;
        camY = viewerY;
        camZ = viewerZ;
        frame++;
        VisibilityBfs.run(frustum, viewerX, viewerY, viewerZ, frame);
    }

    public static boolean updateChunks(EntityLivingBase viewer, long deadlineNanos, boolean force) {
        return SectionScheduler.update(viewer, deadlineNanos, force);
    }

    public static void drawOpaque() {
        TerrainDraw.drawOpaque();
    }

    public static int drawTranslucent() {
        return TerrainDraw.drawTranslucent();
    }

    public static void markDirty(WorldRenderer r) {
        SectionScheduler.markDirty(r);
    }

    public static boolean isBlockVisible(int x, int y, int z) {
        return VisibilityBfs.isBlockVisible(x, y, z);
    }

    public static String debugInfo() {
        return "C: " + VisibilityBfs.visibleCount() + "/" + VisibilityBfs.totalCount() + ". F: " + VisibilityBfs.frustumCulled() + ", O: "
                + VisibilityBfs.bfsCulled() + ", E: " + VisibilityBfs.emptyCount();
    }

    /** Sections queued for a rebuild plus sections whose mesh is in flight or awaiting commit (testbot settle signal, 7.2). */
    public static int pendingSections() {
        return SectionScheduler.pendingTotal();
    }

    /** Monotonic number of committed section meshes (testbot settle signal, 7.2). */
    public static long sectionCommits() {
        return SectionCommit.commitCount();
    }

    public static void shutdown() {
        releaseGrid();
        TerrainDraw.shutdown();
        SectionCommit.shutdown();
        SectionScheduler.reset();
        VisibilityBfs.reset();
    }

    static float partialTicks() { return partialTicks; }
    static int frame() { return frame; }
    static double camX() { return camX; }
    static double camY() { return camY; }
    static double camZ() { return camZ; }
}
