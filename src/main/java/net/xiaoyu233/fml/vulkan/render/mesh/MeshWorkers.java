package net.xiaoyu233.fml.vulkan.render.mesh;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.AxisAlignedBB;
import net.minecraft.WorldRenderer;
import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.render.tess.MeshSink;
import net.xiaoyu233.fml.vulkan.render.tess.MeshWorkerThread;
import net.xiaoyu233.fml.vulkan.render.tess.NativeTess;
import net.xiaoyu233.fml.vulkan.render.tess.TessBridge;
import net.xiaoyu233.fml.vulkan.render.tess.TessTarget;

/**
 * Mesh worker pool (1.15, 5.8): priority queue (0 = near set first, FIFO within a priority), epoch-based discard, render-thread
 * {@link #helpOnce()} and an independent background {@link #pool()}.
 */
public final class MeshWorkers {
    private static final Object LIFECYCLE = new Object();
    private static final AtomicInteger EPOCH = new AtomicInteger();
    private static final AtomicLong SEQ = new AtomicLong();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final Object IDLE = new Object();

    private static final PriorityBlockingQueue<Entry> QUEUE = new PriorityBlockingQueue<>(256);
    /** Tasks whose section holds a block that is not worker-safe (2.3 #11): meshed only by {@link #helpOnce()} on the render thread. */
    private static final ConcurrentLinkedQueue<Entry> RENDER_ONLY = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<MeshResult> RESULTS = new ConcurrentLinkedQueue<>();

    private static volatile boolean running;
    private static volatile Worker[] workers = new Worker[0];
    private static volatile int configuredThreads = defaultThreads();
    private static volatile ThreadPoolExecutor pool;
    private static QuadBuffer helpBuffer;
    /** Workers that outlived stop()'s bounded join; their slot indices stay reserved until they exit. Guarded by LIFECYCLE. */
    private static final List<Worker> LEAKED = new ArrayList<>();

    private static volatile Method cleanPoolMethod;
    private static volatile boolean cleanPoolResolved;

    private MeshWorkers() {
    }

    private static int defaultThreads() {
        int n = Math.max(1, Math.min(ThreadSlots.MAX_WORKERS, Runtime.getRuntime().availableProcessors() - 2));
        try {
            String p = System.getProperty("fml.vulkan.meshThreads");
            if (p != null) {
                n = Integer.parseInt(p.trim());
            }
        } catch (RuntimeException ignored) {
        }
        return Math.max(1, Math.min(ThreadSlots.MAX_WORKERS, n));
    }

    /** Growable int buffer that receives the raw quads of one thread's NativeTess ({@link NativeTess#meshSink()} returns it). */
    static final class QuadBuffer implements MeshSink {
        int[] data = new int[8 * 4 * 256];
        int size;

        @Override
        public void quads(int[] raw, int intCount, int vertexCount) {
            int need = size + intCount;
            if (need > data.length) {
                data = java.util.Arrays.copyOf(data, Math.max(need, data.length * 2));
            }
            System.arraycopy(raw, 0, data, size, intCount);
            size = need;
        }

        void reset() {
            size = 0;
            if (data.length > (1 << 22)) {
                data = new int[8 * 4 * 256];
            }
        }
    }

    private record Entry(MeshTask task, int priority, long seq) implements Comparable<Entry> {
        @Override
        public int compareTo(Entry o) {
            int c = Integer.compare(priority, o.priority);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }

    private static final class Worker extends MeshWorkerThread {
        final QuadBuffer buffer;
        /** Set by stop(): this thread leaves its loop even if a later start() has set {@code running} again. */
        volatile boolean retired;

        Worker(int k, QuadBuffer buffer, NativeTess tess) {
            super(null, "fml-mesh-" + k, tess, k);
            this.buffer = buffer;
        }

        @Override
        public void run() {
            loop(this);
        }
    }

    public static void start(int threads) {
        synchronized (LIFECYCLE) {
            if (running) {
                stop();
            }
            int requested = Math.max(1, Math.min(ThreadSlots.MAX_WORKERS, threads));
            configuredThreads = requested;
            RenderTypeAllowList.refresh();
            MeshWorkerThread.resetIndexCounter();
            // A straggler from an earlier generation still owns its slot 2 + k (its NativeTess and Block bounds); never reuse it.
            LEAKED.removeIf(w -> !w.isAlive());
            boolean[] reserved = new boolean[ThreadSlots.MAX_WORKERS];
            for (Worker w : LEAKED) {
                reserved[w.workerIndex()] = true;
            }
            List<Integer> free = new ArrayList<>();
            for (int k = 0; k < ThreadSlots.MAX_WORKERS && free.size() < requested; k++) {
                if (!reserved[k]) {
                    free.add(k);
                }
            }
            int n = free.size();
            if (n < requested) {
                VkLog.LOGGER.warn("Only {} mesh worker slot(s) free (requested {}); {} straggler worker(s) still alive", n, requested, LEAKED.size());
            }
            running = true;
            Worker[] ws = new Worker[n];
            for (int i = 0; i < n; i++) {
                QuadBuffer buf = new QuadBuffer();
                ws[i] = new Worker(free.get(i), buf, TessBridge.createWorkerTess(buf));
            }
            workers = ws;
            for (Worker w : ws) {
                w.start();
            }
        }
    }

    /** {@code bumpEpoch + interrupt + bounded join}; idempotent. Threads that miss the join keep their slot reserved (see start). */
    public static void stop() {
        synchronized (LIFECYCLE) {
            Worker[] ws = workers;
            boolean wasRunning = running;
            if (wasRunning) {
                bumpEpoch("stop");
            }
            running = false;
            for (Worker w : ws) {
                w.retired = true;
                w.interrupt();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (Worker w : ws) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                try {
                    if (left > 0) {
                        w.join(left);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            LEAKED.removeIf(w -> !w.isAlive());
            for (Worker w : ws) {
                if (w.isAlive()) {
                    VkLog.LOGGER.warn("Mesh worker {} did not stop in time; slot {} stays reserved until it exits", w.getName(), w.workerIndex());
                    LEAKED.add(w);
                }
            }
            workers = new Worker[0];
            clearQueue();
            drainResults();
            MeshWorkerThread.resetIndexCounter();
        }
    }

    /**
     * Final shutdown, called once the Vulkan device is idle: {@link #stop()}, then the background pool is shut down and given a
     * bounded wait so no pool task touches the device after it is destroyed. A later {@link #pool()} call creates a fresh pool.
     */
    public static void shutdown() {
        stop();
        ThreadPoolExecutor p;
        synchronized (MeshWorkers.class) {
            p = pool;
            pool = null;
        }
        if (p == null) {
            return;
        }
        p.shutdown();
        try {
            if (!p.awaitTermination(5, TimeUnit.SECONDS)) {
                VkLog.LOGGER.warn("Background pool did not terminate in time; {} task(s) may still be running", p.getActiveCount());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static int epoch() {
        return EPOCH.get();
    }

    /** Discards queued tasks, waits up to 2 s for in-flight ones; their results carry the old epoch and are released by pollResult. */
    public static void bumpEpoch(String reason) {
        int e = EPOCH.incrementAndGet();
        clearQueue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        synchronized (IDLE) {
            while (IN_FLIGHT.get() > 0) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    break;
                }
                try {
                    IDLE.wait(Math.min(left, 20));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        int stuck = IN_FLIGHT.get();
        VkLog.LOGGER.info("Mesh epoch -> {} ({}){}", e, reason, stuck > 0 ? ", " + stuck + " task(s) still in flight, results will be discarded" : "");
    }

    private static void clearQueue() {
        List<Entry> drained = new ArrayList<>();
        QUEUE.drainTo(drained);
        Entry r;
        while ((r = RENDER_ONLY.poll()) != null) {
            drained.add(r);
        }
        for (Entry en : drained) {
            releaseSnapshot(en.task);
        }
    }

    private static void drainResults() {
        MeshResult r;
        while ((r = RESULTS.poll()) != null) {
            r.release();
        }
    }

    public static void submit(MeshTask task, int priority) {
        // Use the epoch the snapshot was captured under: if the world was reset between capture() and submit(),
        // the task stays stale and runTask / pollResult discard it instead of committing old-world data.
        SectionSnapshot snap = task.snapshot();
        task.setEpoch(snap != null ? snap.epoch() : EPOCH.get());
        if (!running) {
            // Submitted before start() or after stop(): nothing will run it, so give the snapshot back now.
            releaseSnapshot(task);
            return;
        }
        Entry entry = new Entry(task, priority, SEQ.getAndIncrement());
        if (meshableOnWorker(task, snap)) {
            QUEUE.add(entry);
        } else {
            RENDER_ONLY.add(entry);
        }
        if (!running) {
            // stop() ran between the check and the add; its clearQueue may have missed this entry.
            clearQueue();
        }
    }

    /**
     * False when the section under {@code task} contains a block outside the render-type allow list (2.3 #11): such a task is meshed on
     * the render thread against the same snapshot. Resorts meshing nothing are always worker tasks. Render thread only (submit).
     */
    private static boolean meshableOnWorker(MeshTask task, SectionSnapshot snap) {
        if (task.resortOnly() || snap == null) {
            return true;
        }
        WorldRenderer wr = task.target();
        if (wr == null) {
            return true;
        }
        // Same origin SectionMeshBuilder.build uses, so the scan covers exactly the cells the builder will mesh.
        int posX = wr.posXMinus + wr.posXClip;
        int posY = wr.posYMinus + wr.posYClip;
        int posZ = wr.posZMinus + wr.posZClip;
        return RenderTypeAllowList.sectionWorkerSafe(snap, posX, posY, posZ);
    }

    /** Background pool (atlas decode, PNG encode, pipeline warm-up); independent of the mesh queue, usable before start(). */
    public static Executor pool() {
        ThreadPoolExecutor p = pool;
        if (p == null) {
            synchronized (MeshWorkers.class) {
                p = pool;
                if (p == null) {
                    int n = Math.max(2, configuredThreads / 2);
                    AtomicInteger id = new AtomicInteger();
                    p = new ThreadPoolExecutor(n, n, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                        Thread t = new Thread(r, "fml-pool-" + id.getAndIncrement());
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        return t;
                    });
                    p.allowCoreThreadTimeOut(true);
                    pool = p;
                }
            }
        }
        return p;
    }

    /**
     * Render thread: runs one queued priority-0 task inline. RenderBlocks writes through {@code Tessellator.instance}, which resolves to
     * {@link TessBridge#renderThread()} on this thread, so the task meshes on that instance. Its target, sink and translation are
     * restored afterwards; it is left alone (returns false) while it is mid-draw.
     */
    /** True while a render-thread-only task is queued (SectionScheduler runs them with the frame's left-over budget). */
    public static boolean hasRenderOnlyWork() {
        return !RENDER_ONLY.isEmpty();
    }

    public static boolean helpOnce() {
        NativeTess tess = TessBridge.renderThread();
        if (tess.isDrawing()) {
            return false;
        }
        Entry e = RENDER_ONLY.poll();
        if (e == null) {
            e = QUEUE.peek();
            if (e == null || e.priority != 0 || !QUEUE.remove(e)) {
                return false;
            }
        }
        IN_FLIGHT.incrementAndGet();
        TessTarget savedTarget = tess.target();
        MeshSink savedSink = tess.meshSink();
        double ox = tess.xOffset(), oy = tess.yOffset(), oz = tess.zOffset();
        try {
            if (helpBuffer == null) {
                helpBuffer = new QuadBuffer();
            }
            runTask(e.task, tess, helpBuffer);
        } finally {
            try {
                if (tess.isDrawing()) {
                    // runTask failed mid-draw: close the draw so the render thread's own tessellation is usable again.
                    tess.draw();
                }
            } catch (Throwable ignored) {
            }
            tess.setTarget(savedTarget);
            tess.setMeshSink(savedSink);
            tess.setTranslation(ox, oy, oz);
            finishFlight();
        }
        return true;
    }

    public static MeshResult pollResult() {
        MeshResult r;
        while ((r = RESULTS.poll()) != null) {
            if (r.epoch() == EPOCH.get()) {
                return r;
            }
            r.release();
        }
        return null;
    }

    private static void finishFlight() {
        if (IN_FLIGHT.decrementAndGet() <= 0) {
            synchronized (IDLE) {
                IDLE.notifyAll();
            }
        }
    }

    private static void loop(Worker w) {
        while (!w.retired && running) {
            Entry e;
            try {
                e = QUEUE.take();
            } catch (InterruptedException ie) {
                break;
            }
            IN_FLIGHT.incrementAndGet();
            try {
                runTask(e.task, w.tess(), w.buffer);
            } catch (Throwable t) {
                // runTask already guards; never let a worker die
            } finally {
                finishFlight();
            }
        }
    }

    private static volatile boolean loggedFailure;

    private static void runTask(MeshTask task, NativeTess tess, QuadBuffer buffer) {
        try {
            if (task.epoch() != EPOCH.get()) {
                return;
            }
            if (task.resortOnly()) {
                // A MeshTask carries no previous translucent layer yet (§2.7 input = the section's translucentQuads), so a resort run here
                // would have no data. Dropping it keeps the section's current ordering instead of committing an empty layer.
                return;
            }
            buffer.reset();
            MeshResult r = SectionMeshBuilder.build(task, tess, null);
            if (r != null) {
                r.setEpoch(task.epoch());
                // Re-check after the task ran: a stop() or epoch bump may have landed meanwhile. Late adds are also released by pollResult.
                if (running && r.epoch() == EPOCH.get()) {
                    RESULTS.add(r);
                } else {
                    r.release();
                }
            }
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                VkLog.LOGGER.error("Mesh task failed (logged once): " + task, t);
            }
        } finally {
            // Workers own their thread-local AABB pool and clean it after each task. The render thread (helpOnce) must not:
            // AABBs handed out earlier in the frame are still live there, and its pool is cleaned once per frame in runGameLoop.
            if (Thread.currentThread() instanceof MeshWorkerThread) {
                try {
                    AxisAlignedBB.getAABBPool().cleanPool();
                } catch (Throwable ignored) {
                }
            }
            releaseSnapshot(task);
        }
    }

    private static void releaseSnapshot(MeshTask task) {
        SectionSnapshot s = task.snapshot();
        if (s == null) {
            return;
        }
        try {
            cleanSnapshotPool(s);
        } catch (Throwable ignored) {
        }
        try {
            s.release();
        } catch (Throwable ignored) {
        }
    }

    private static void cleanSnapshotPool(SectionSnapshot s) {
        s.cleanPool();
    }
}
