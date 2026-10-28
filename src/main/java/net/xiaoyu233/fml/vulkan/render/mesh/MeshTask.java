package net.xiaoyu233.fml.vulkan.render.mesh;

import net.minecraft.WorldRenderer;

/** One meshing or resorting job (2.2). */
public final class MeshTask {
    private final WorldRenderer target;
    private final int version;
    private final SectionSnapshot snapshot;
    private final double camX, camY, camZ;
    private final boolean resortOnly;
    private volatile int epoch;

    public MeshTask(WorldRenderer target, int version, SectionSnapshot snapshot, double camX, double camY, double camZ, boolean resortOnly) {
        this.target = target;
        this.version = version;
        this.snapshot = snapshot;
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
        this.resortOnly = resortOnly;
    }

    public WorldRenderer target() {
        return target;
    }

    public int version() {
        return version;
    }

    public SectionSnapshot snapshot() {
        return snapshot;
    }

    public double camX() {
        return camX;
    }

    public double camY() {
        return camY;
    }

    public double camZ() {
        return camZ;
    }

    public boolean resortOnly() {
        return resortOnly;
    }

    /** Epoch stamped by {@link MeshWorkers#submit}. */
    public int epoch() {
        return epoch;
    }

    void setEpoch(int e) {
        epoch = e;
    }

    /** Coordinates for logging. */
    @Override
    public String toString() {
        return "MeshTask[" + target + " v" + version + (resortOnly ? " resort" : "") + " epoch " + epoch + "]";
    }
}
