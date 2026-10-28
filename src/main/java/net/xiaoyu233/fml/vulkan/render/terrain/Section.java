package net.xiaoyu233.fml.vulkan.render.terrain;

import net.minecraft.WorldRenderer;
import net.xiaoyu233.fml.vulkan.render.gpu.GpuAllocation;

/** Native state of one 16x16x16 render section (2.1). Render thread only. Shared type of NR-09: fields are package-private on purpose. */
public final class Section {
    public static final int EMPTY = 0, DIRTY = 1, QUEUED = 2, BUILDING = 3, READY = 4;
    public static final int SOLID = 0, CUTOUT = 1, TRANSLUCENT = 2, LAYERS = 3;
    /** Fully connected visibility (all 36 face pairs): used for unbuilt / dirty / fallback sections. */
    public static final long FULLY_CONNECTED = (1L << 36) - 1L;

    final WorldRenderer owner;
    int state = EMPTY;
    int version;
    int builtVersion = -1;
    final GpuAllocation[] alloc = new GpuAllocation[LAYERS];
    final int[] quads = new int[LAYERS];
    long visibility = FULLY_CONNECTED;
    /** CPU copy for re-sorting: per quad centroid (3 floats) in {@link #translucentCentroids}, vertices stay in {@link #translucentVerts}. */
    float[] translucentCentroids;
    java.nio.ByteBuffer translucentVerts;
    double lastSortX = Double.NaN, lastSortY = Double.NaN, lastSortZ = Double.NaN;
    int frameVisited = -1;
    int bfsIndex;
    int bfsDirections;
    long neighbourWaitSince;
    boolean resortPending;
    /** Section origin (block coordinates) as last assigned by the ring; refreshed by {@link TerrainGrid#syncPosition}. */
    int x, y, z;

    /** Factory used by the WorldRenderer mixin. */
    public static Section create(WorldRenderer owner) {
        return new Section(owner);
    }

    Section(WorldRenderer owner) {
        this.owner = owner;
    }

    public int state() { return state; }
    public int version() { return version; }
    public long visibility() { return visibility; }
    public boolean hasLayer(int layer) { return layer >= 0 && layer < LAYERS && quads[layer] > 0; }
    public int quads(int layer) { return quads[layer]; }
    public WorldRenderer owner() { return owner; }
}
