package net.xiaoyu233.fml.vulkan.render.tess;

import java.util.concurrent.atomic.AtomicInteger;
import net.xiaoyu233.fml.vulkan.render.mesh.ThreadSlots;

/** Base class of the mesh worker threads: carries the thread's {@link NativeTess} in a final field (1.6). NR-10 creates the instances. */
public abstract class MeshWorkerThread extends Thread {
    private static final AtomicInteger NEXT_INDEX = new AtomicInteger();

    private final NativeTess tess;
    private final int workerIndex;

    /** Legacy form: takes the next index of a global counter; prefer the explicit-index constructor (reset with {@link #resetIndexCounter()}). */
    protected MeshWorkerThread(Runnable body, String name, NativeTess tess) {
        this(body, name, tess, Math.floorMod(NEXT_INDEX.getAndIncrement(), ThreadSlots.MAX_WORKERS));
    }

    /** {@code workerIndex} is the logical worker index k (0..MAX_WORKERS-1) chosen by MeshWorkers; unique among live workers. */
    protected MeshWorkerThread(Runnable body, String name, NativeTess tess, int workerIndex) {
        super(body, name);
        if (workerIndex < 0 || workerIndex >= ThreadSlots.MAX_WORKERS) {
            throw new IllegalArgumentException("workerIndex " + workerIndex);
        }
        this.tess = tess;
        this.workerIndex = workerIndex;
        setDaemon(true);
    }

    /** MeshWorkers.start/stop call this so the legacy counter restarts at 0 with every worker generation. */
    public static void resetIndexCounter() {
        NEXT_INDEX.set(0);
    }

    /** Read by {@link TessBridge#current()}. */
    public final NativeTess tess() {
        return tess;
    }

    /** 0..{@link ThreadSlots#MAX_WORKERS}-1. */
    public final int workerIndex() {
        return workerIndex;
    }
}
