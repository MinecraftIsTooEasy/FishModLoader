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
        // The new renderers were marked dirty in their constructor / loadRenderers BEFORE this call, and reset() just dropped those
        // queue entries (state DIRTY, needsUpdate=true). markRenderersForNewPosition only re-marks renderers that move, and
        // RenderGlobal.markBlocksForUpdate skips renderers with needsUpdate=true, so every unmoved section stayed unbuilt forever and
        // ignored all later block edits ("placed blocks invisible in some sections"). Queue the whole new grid again.
        for (WorldRenderer r : grid) {
            if (r != null) {
                SectionScheduler.markDirty(r);
            }
        }
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
        long t0 = System.nanoTime();
        try {
            return SectionScheduler.update(viewer, deadlineNanos, force);
        } finally {
            net.xiaoyu233.fml.vulkan.render.frame.FrameStats.noteSched(System.nanoTime() - t0);
        }
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

    /** Debug: state of the section that renders block (x, y, z) (water scene / testbot). */
    public static String sectionDebug(int x, int y, int z) {
        WorldRenderer r = TerrainGrid.rendererFor(x, y, z);
        if (r == null) {
            return "none";
        }
        Section s = TerrainGrid.sectionOf(r);
        return "state=" + s.state + " v=" + s.version + " built=" + s.builtVersion + " needsUpdate=" + r.needsUpdate + " quads=" + s.quads[0] + "/"
                + s.quads[1] + "/" + s.quads[2] + " " + SectionScheduler.debugCounts();
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
