package net.xiaoyu233.fml.vulkan.render.mesh;

import net.xiaoyu233.fml.vulkan.render.tess.MeshWorkerThread;

/**
 * Per-thread slot numbering of the mesh pipeline (1.15, 2.3 #2). {@link #index()} answers 1 for the render thread and every other
 * non-worker thread and {@code 2 + k} for mesh worker k; the server thread (slot 0) is distinguished by {@code MinecraftMeshMixin},
 * which owns {@code Minecraft.getThreadIndex()}.
 */
public final class ThreadSlots {
    public static final int MAX_WORKERS = 8;

    private ThreadSlots() {
    }

    /** Slot of the calling thread, never 0 (the server thread check stays in the Minecraft mixin). */
    public static int index() {
        return Thread.currentThread() instanceof MeshWorkerThread w ? 2 + w.workerIndex() : 1;
    }

    /** {@code 2 + MAX_WORKERS}. */
    public static int slotCount() {
        return 2 + MAX_WORKERS;
    }
}
