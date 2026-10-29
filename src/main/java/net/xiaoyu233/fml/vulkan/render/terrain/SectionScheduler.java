package net.xiaoyu233.fml.vulkan.render.terrain;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.PriorityQueue;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.EntityLivingBase;
import net.minecraft.Minecraft;
import net.minecraft.World;
import net.minecraft.WorldRenderer;
import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.render.frame.FrameStats;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshResult;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshTask;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshWorkers;
import net.xiaoyu233.fml.vulkan.render.mesh.SectionSnapshot;
import net.xiaoyu233.fml.vulkan.render.mesh.ThreadSlots;

/** Rebuild scheduling (2.2): near set, far priority queue, neighbour gate, capture budget. Render thread. NR-09 subtask S2. */
public final class SectionScheduler {
    private static final float NEAR_DIST2 = 256.0F;
    private static final float GATE_DIST2 = 24.0F * 24.0F;
    private static final long GATE_WAIT_NANOS = 1_500_000_000L;
    private static final int MAX_POPS = 256;
    /** Neighbour-gate evaluations per frame (each may run 8 chunkExists calls); deferred entries do not use MAX_POPS. */
    private static final int MAX_GATE_CHECKS = 256;
    /** A gated or resort-deferred entry is parked off the heap and re-examined after this many frames. */
    private static final int GATE_RECHECK_FRAMES = 4;
    private static final long JOIN_TIMEOUT_NANOS = 10_000_000_000L;
    private static final int RETRY_FRAMES = 120;

    private static final int MESH_THREADS = Math.max(1, Math.min(ThreadSlots.MAX_WORKERS,
            Integer.getInteger("fml.vulkan.meshThreads", Math.max(2, Runtime.getRuntime().availableProcessors() - 2))));
    private static final int IN_FLIGHT_CAP = 2 * MESH_THREADS;
    private static final long CAPTURE_NANOS = (long) (prop("fml.vulkan.captureMs", 1.5) * 1_000_000.0);
    private static final int CAPTURES_PER_FRAME = Integer.getInteger("fml.vulkan.capturesPerFrame", 64);
    /** Base (busy-frame) budget, from VulkanConfig (single source of truth). */
    private static final long BASE_COMMIT_NANOS = (long) (VulkanConfig.sectionCommitMs() * 1_000_000.0);
    private static final long BASE_UPLOAD_BYTES = VulkanConfig.sectionUploadMiB() * 1024L * 1024L;
    private static final long IDLE_UPLOAD_BYTES = Math.max(BASE_UPLOAD_BYTES, 16L * 1024L * 1024L);
    private static final boolean ADAPTIVE = VulkanConfig.sectionAdaptive();
    private static final double FRAME_TARGET_MS = 1000.0 / 60.0;
    private static final double BUSY_FRAME_MS = 4.0;
    private static final long MAX_COMMIT_NANOS = Math.max(BASE_COMMIT_NANOS, 3_000_000L);
    /** Effective budget of the current frame (adaptive or the base values). */
    private static long COMMIT_NANOS = BASE_COMMIT_NANOS;
    private static long UPLOAD_BYTES = BASE_UPLOAD_BYTES;
    static {
        VkLog.LOGGER.info("SectionScheduler budget: commit {} ms, upload {} MiB (adaptive={}: commit up to {} ms, upload up to {} MiB when frame < {} ms)",
                BASE_COMMIT_NANOS / 1e6, BASE_UPLOAD_BYTES >> 20, ADAPTIVE, MAX_COMMIT_NANOS / 1e6, IDLE_UPLOAD_BYTES >> 20, BUSY_FRAME_MS);
    }

    private static final class Entry {
        final Section section;
        long key;
        int frame;
        /** Parked in GATED until this frame (gate or resort wait); ignored while on the heap. */
        int gatedUntil;

        Entry(Section section) {
            this.section = section;
        }
    }

    private record Resort(int version, double x, double y, double z) {
    }

    private static final class Pending {
        final Section section;
        final MeshResult result;

        Pending(Section section, MeshResult result) {
            this.section = section;
            this.result = result;
        }
    }

    private static final PriorityQueue<Entry> HEAP = new PriorityQueue<>(256, (a, b) -> Long.compare(a.key, b.key));
    /** Valid heap entry per queued section (stale heap entries are dropped lazily on poll). */
    private static final IdentityHashMap<Section, Entry> ENTRIES = new IdentityHashMap<>();
    private static final IdentityHashMap<Section, Boolean> IN_FLIGHT = new IdentityHashMap<>();
    private static final IdentityHashMap<Section, Boolean> NEAR_OUT = new IdentityHashMap<>();
    /** Resort tasks in flight (never in IN_FLIGHT): section -> version and camera the task was submitted with. */
    private static final IdentityHashMap<Section, Resort> RESORT_OUT = new IdentityHashMap<>();
    private static final ArrayList<Section> PARKED = new ArrayList<>();
    /** Entries held off the heap (neighbour gate or outstanding resort) until {@link Entry#gatedUntil}; not popped meanwhile. */
    private static final ArrayList<Entry> GATED = new ArrayList<>();
    private static final ArrayList<Section> NEAR_LIST = new ArrayList<>();
    /** Results whose commit hit arena OOM: the section stays IN_FLIGHT (BUILDING) and the result is retried each frame. */
    private static final ArrayList<Pending> PENDING = new ArrayList<>();

    private static int unavailableUntilFrame = -1;
    /** Viewer of the current {@link #updateImpl} call (render thread); used by handleResult for the near test. */
    private static EntityLivingBase nearViewer;
    /**
     * Commit budget of the current rendered frame (keyed by {@code TerrainRenderer.frame()}). The near-set join and both far
     * commit loops of a frame share one counter: nanoseconds spent inside commits (worker waits excluded) and, in SectionCommit,
     * the uploaded bytes (commitResorted included).
     */
    private static int budgetFrame = Integer.MIN_VALUE;
    private static long budgetSpentNanos;
    private static boolean loggedUnavailable;
    private static boolean loggedError;

    private SectionScheduler() {
    }

    private static double prop(String name, double def) {
        try {
            return Double.parseDouble(System.getProperty(name, Double.toString(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** {@code TerrainRenderer.updateChunks}: returns true when nothing is queued, building or awaiting commit. */
    static boolean update(EntityLivingBase viewer, long deadlineNanos, boolean force) {
        try {
            return updateImpl(viewer, deadlineNanos, force);
        } catch (RuntimeException | LinkageError e) {
            if (!loggedError) {
                loggedError = true;
                VkLog.LOGGER.error("SectionScheduler.update failed (suppressing further reports)", e);
            }
            return false;
        }
    }

    private static boolean idle() {
        return ENTRIES.isEmpty() && IN_FLIGHT.isEmpty();
    }

    private static boolean updateImpl(EntityLivingBase viewer, long deadlineNanos, boolean force) {
        int frame = TerrainRenderer.frame();
        beginBudgetFrame(frame);
        if (viewer == null || (idle() && PARKED.isEmpty())) {
            return idle();
        }
        nearViewer = viewer;
        retryPending();
        if (frame < unavailableUntilFrame) {
            return idle();
        }
        if (!PARKED.isEmpty()) {
            // retry after an unavailable period: requeue parked sections
            for (int i = 0; i < PARKED.size(); i++) {
                Section s = PARKED.get(i);
                if (s.state == Section.DIRTY) {
                    enqueue(s);
                }
            }
            PARKED.clear();
        }

        // ---- near set: constant-size neighbourhood lookup (or the whole queue in force mode) -------------------
        collectNear(viewer, force);
        if (!NEAR_LIST.isEmpty()) {
            for (int i = 0; i < NEAR_LIST.size(); i++) {
                Section s = NEAR_LIST.get(i);
                if (RESORT_OUT.containsKey(s)) {
                    continue; // resort outstanding: stays queued until its result is applied
                }
                ENTRIES.remove(s);
                if (!submit(s, 0, true)) {
                    break;
                }
            }
            NEAR_LIST.clear();
            joinNear();
        }
        if (frame < unavailableUntilFrame) {
            return idle();
        }

        // ---- far set -----------------------------------------------------------------------------------------
        commitLoop(deadlineNanos);
        submitFar(viewer, frame);
        commitLoop(deadlineNanos);
        // far tasks of sections with a non-worker-safe block are only run by helpOnce on this thread: give them the frame budget left
        while (budgetSpentNanos < COMMIT_NANOS && MeshWorkers.hasRenderOnlyWork()) {
            long t0 = System.nanoTime();
            boolean ran = MeshWorkers.helpOnce();
            budgetSpentNanos += System.nanoTime() - t0;
            if (!ran) {
                break;
            }
            MeshResult res;
            while ((res = MeshWorkers.pollResult()) != null) {
                commitResult(res);
            }
        }
        if (!NEAR_OUT.isEmpty()) {
            joinNear(); // near sections re-submitted by handleResult during the far commits
        }
        return idle();
    }

    private static void collectNear(EntityLivingBase viewer, boolean force) {
        NEAR_LIST.clear();
        if (ENTRIES.isEmpty()) {
            return;
        }
        if (force) {
            for (Section s : ENTRIES.keySet()) {
                WorldRenderer r = s.owner;
                if (r.isInFrustum || r.distanceToEntitySquared(viewer) <= NEAR_DIST2) {
                    NEAR_LIST.add(s);
                }
            }
            return;
        }
        int vx = (int) Math.floor(viewer.posX), vy = (int) Math.floor(viewer.posY), vz = (int) Math.floor(viewer.posZ);
        for (int dy = -2; dy <= 2; dy++) {
            int by = vy + dy * 16;
            if (by < 0 || by > 255) {
                continue;
            }
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    WorldRenderer r = TerrainGrid.rendererFor(vx + dx * 16, by, vz + dz * 16);
                    if (r == null) {
                        continue;
                    }
                    Section s = TerrainGrid.sectionOf(r);
                    if (s != null && s.state == Section.QUEUED && ENTRIES.containsKey(s) && r.distanceToEntitySquared(viewer) <= NEAR_DIST2) {
                        NEAR_LIST.add(s);
                    }
                }
            }
        }
    }

    /** Captures and submits one section; false when the async path is unavailable (section parked). */
    private static boolean submit(Section s, int priority, boolean near) {
        WorldRenderer r = s.owner;
        World world = r.worldObj;
        if (world == null) {
            s.state = Section.DIRTY;
            return true;
        }
        s.x = r.posXPlus - 8;
        s.y = r.posYPlus - 8;
        s.z = r.posZPlus - 8;
        try {
            SectionSnapshot snap = SectionSnapshot.capture(world, s.x, s.y, s.z);
            s.state = Section.BUILDING;
            r.needsUpdate = false;
            s.neighbourWaitSince = 0L;
            IN_FLIGHT.put(s, Boolean.TRUE);
            if (near) {
                NEAR_OUT.put(s, Boolean.TRUE);
            }
            try {
                MeshWorkers.submit(new MeshTask(r, s.version, snap, TerrainRenderer.camX(), TerrainRenderer.camY(), TerrainRenderer.camZ(), false), priority);
            } catch (RuntimeException e) {
                IN_FLIGHT.remove(s);
                NEAR_OUT.remove(s);
                snap.release();
                throw e;
            }
            return true;
        } catch (UnsupportedOperationException e) {
            if (!loggedUnavailable) {
                loggedUnavailable = true;
                VkLog.LOGGER.warn("Mesh workers unavailable (NR-10 not landed): falling back to render-thread meshing");
            }
            s.state = Section.DIRTY;
            if (SyncMesher.mesh(s)) {
                return true;
            }
            PARKED.add(s);
            unavailableUntilFrame = TerrainRenderer.frame() + RETRY_FRAMES;
            return false;
        }
    }

    private static void joinNear() {
        long start = System.nanoTime();
        while (!NEAR_OUT.isEmpty()) {
            MeshResult res = MeshWorkers.pollResult();
            if (res != null) {
                handleResult(res);
                continue;
            }
            if (!MeshWorkers.helpOnce()) {
                if (System.nanoTime() - start > JOIN_TIMEOUT_NANOS) {
                    VkLog.LOGGER.error("Near-set mesh join timed out; {} sections left to later frames", NEAR_OUT.size());
                    for (Section s : NEAR_OUT.keySet()) {
                        IN_FLIGHT.remove(s);
                        s.state = Section.DIRTY;
                        enqueue(s);
                    }
                    NEAR_OUT.clear();
                    return;
                }
                LockSupport.parkNanos(50_000L);
            }
        }
    }

    /** TranslucentOrder: registers a resort task about to be submitted for {@code s} (render thread). */
    static void noteResortSubmitted(Section s, double camX, double camY, double camZ) {
        RESORT_OUT.put(s, new Resort(s.version, camX, camY, camZ));
    }

    /** TranslucentOrder: forgets a resort task whose submission failed (render thread). */
    static void noteResortDropped(Section s) {
        RESORT_OUT.remove(s);
    }

    /**
     * Applies one polled result; returns true when committed. A result whose version equals the registered resort task
     * of its section is a resort (the section is READY, not in flight); anything else is a meshing result.
     */
    private static boolean handleResult(MeshResult res) {
        Section s = null;
        try {
            WorldRenderer target = res.target();
            s = target == null ? null : TerrainGrid.sectionOf(target);
        } catch (RuntimeException e) {
            // result without target: drop
        }
        if (s == null) {
            res.release();
            return false;
        }
        // A section with a resort outstanding is never given a mesh task (submit paths skip it), so a result for it is the resort.
        Resort rs = RESORT_OUT.remove(s);
        if (rs != null) {
            s.resortPending = false;
            try {
                return applyResort(s, res, rs);
            } finally {
                res.release();
            }
        }
        if (IN_FLIGHT.remove(s) == null) {
            res.release();
            return false;
        }
        NEAR_OUT.remove(s);
        if (res.version() < s.version) {
            res.release();
            if (isNear(s)) {
                // edited while building inside the near radius: mesh it now so the frame draws the fresh mesh
                resubmitNear(s);
            } else {
                s.state = Section.DIRTY;
                s.owner.needsUpdate = true;
                enqueue(s);
            }
            return false;
        }
        boolean ok = SectionCommit.commit(s, res);
        if (ok) {
            if (s.state == Section.BUILDING) {
                s.state = Section.READY;
            }
            return true;
        }
        // commit() returns false here only on arena OOM: the result is current (checked above) and was neither released nor
        // applied. Keep the section in flight and retry the same result in retryPending() instead of re-meshing it.
        IN_FLIGHT.put(s, Boolean.TRUE);
        PENDING.add(new Pending(s, res));
        return false;
    }

    /** True when {@code s} is inside the near radius of the current viewer (same distance test as collectNear). */
    private static boolean isNear(Section s) {
        EntityLivingBase v = nearViewer;
        return v != null && s.owner.distanceToEntitySquared(v) <= NEAR_DIST2;
    }

    /**
     * Re-submits a near section whose result went stale, as a near task (registered in NEAR_OUT); the caller joins it.
     * Falls back to the far queue when the world is gone (submit would otherwise leave the section unqueued).
     */
    private static void resubmitNear(Section s) {
        if (s.owner.worldObj == null) {
            s.state = Section.DIRTY;
            s.owner.needsUpdate = true;
            enqueue(s);
            return;
        }
        ENTRIES.remove(s);
        s.state = Section.DIRTY;
        submit(s, 0, true); // false = parked (async path unavailable); retried by updateImpl like any parked section
    }

    /** Retries OOM-deferred commits; stale ones are released and the section is requeued. */
    private static void retryPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        ArrayList<Pending> items = new ArrayList<>(PENDING);
        PENDING.clear();
        for (int i = 0; i < items.size(); i++) {
            Pending p = items.get(i);
            Section s = p.section;
            if (p.result.version() < s.version) {
                p.result.release();
                IN_FLIGHT.remove(s);
                s.state = Section.DIRTY;
                s.owner.needsUpdate = true;
                enqueue(s);
            } else if (SectionCommit.commit(s, p.result)) {
                IN_FLIGHT.remove(s);
                if (s.state == Section.BUILDING) {
                    s.state = Section.READY;
                }
            } else {
                PENDING.add(p);
            }
        }
    }

    /** Applies a re-sorted translucent block; false (dropped) when the section changed since the task was submitted. */
    private static boolean applyResort(Section s, MeshResult res, Resort rs) {
        ByteBuffer sorted = res.layer(Section.TRANSLUCENT);
        float[] c = res.translucentCentroids();
        int quads = s.quads[Section.TRANSLUCENT];
        if (s.state != Section.READY || res.version() != s.version || rs.version() != s.version || sorted == null || c == null
                || s.alloc[Section.TRANSLUCENT] == null || s.translucentVerts == null
                || sorted.remaining() != quads * SectionCommit.QUAD_BYTES || c.length < quads * 3
                || s.translucentVerts.capacity() < sorted.remaining()) {
            return false;
        }
        SectionCommit.commitResorted(s, sorted);
        // keep the CPU copy in the new order so the next resort starts from it (same as TranslucentOrder.resortSync)
        s.translucentVerts.clear();
        s.translucentVerts.put(sorted.duplicate());
        s.translucentVerts.flip();
        s.translucentCentroids = c.clone();
        s.lastSortX = rs.x();
        s.lastSortY = rs.y();
        s.lastSortZ = rs.z();
        return true;
    }

    /**
     * Starts the commit budget of {@code frame} once: the near-set join and both far commit loops share its start time, and the
     * byte counter in SectionCommit is reset here (it counts every upload of the frame, including commitResorted).
     */
    private static void beginBudgetFrame(int frame) {
        if (frame != budgetFrame) {
            budgetFrame = frame;
            budgetSpentNanos = 0L;
            SectionCommit.beginFrame();
            COMMIT_NANOS = BASE_COMMIT_NANOS;
            UPLOAD_BYTES = BASE_UPLOAD_BYTES;
            if (ADAPTIVE && !idle()) {
                double lastMs = FrameStats.latest().cpuNanos() / 1e6;
                if (lastMs < BUSY_FRAME_MS) {
                    double ms = Math.max(0.5, Math.min(3.0, FRAME_TARGET_MS - lastMs - 0.3));
                    COMMIT_NANOS = Math.max(BASE_COMMIT_NANOS, Math.min(MAX_COMMIT_NANOS, (long) (ms * 1e6)));
                    UPLOAD_BYTES = IDLE_UPLOAD_BYTES;
                }
            }
        }
    }

    /** Applies one result through handleResult and charges its time to the frame's commit budget. */
    private static boolean commitResult(MeshResult res) {
        long t0 = System.nanoTime();
        try {
            return handleResult(res);
        } finally {
            budgetSpentNanos += System.nanoTime() - t0;
        }
    }

    /**
     * Far commit loop, bounded by the frame budget (shared with the near join and the other loop of the frame), the per-call
     * deadline and the framerate-3 margin. Near-set results are committed by joinNear regardless of the budget (correctness);
     * they still consume it through the shared start time and byte counter.
     */
    private static void commitLoop(long deadlineNanos) {
        boolean first = true;
        while (!IN_FLIGHT.isEmpty()) {
            if (budgetSpentNanos >= COMMIT_NANOS || SectionCommit.uploadedBytesThisFrame() >= UPLOAD_BYTES) {
                break;
            }
            if (!first && deadlineNanos == 0L) {
                break; // near set + one far commit
            }
            // MITE's frame deadline (renderEnd + 1/fps) has usually passed already when the frame is vsync / limiter bound (30 fps on MoltenVK FIFO):
            // honouring it limited the commits to one per frame (about 50 sections / s, a 4600 section world took 90 s). The commit budget
            // (sectionCommitMs / sectionUploadMiB, shared by the frame) bounds the render-thread time instead, so the deadline is not consulted.
            MeshResult res = MeshWorkers.pollResult();
            if (res == null) {
                break;
            }
            first = false;
            commitResult(res);
        }
    }

    /** Moves parked entries whose wait has elapsed back onto the heap (no distance or chunk checks here). */
    private static void wakeGated(int frame) {
        int keep = 0;
        for (int i = 0; i < GATED.size(); i++) {
            Entry e = GATED.get(i);
            if (e.gatedUntil <= frame) {
                HEAP.add(e);
            } else {
                GATED.set(keep++, e);
            }
        }
        GATED.subList(keep, GATED.size()).clear();
    }

    /** Parks a deferred entry off the heap; it is re-examined after {@code frames} frames. */
    private static void park(Entry e, int frame, int frames) {
        e.gatedUntil = frame + frames;
        GATED.add(e);
    }

    private static void submitFar(EntityLivingBase viewer, int frame) {
        wakeGated(frame);
        if (HEAP.isEmpty() || IN_FLIGHT.size() >= IN_FLIGHT_CAP) {
            return;
        }
        long t0 = System.nanoTime();
        int captures = 0;
        int pops = 0;
        int gateChecks = 0;
        long now = t0;
        while (IN_FLIGHT.size() < IN_FLIGHT_CAP && pops < MAX_POPS && !HEAP.isEmpty() && frame >= unavailableUntilFrame) {
            Entry e = HEAP.poll();
            pops++;
            Section s = e.section;
            if (ENTRIES.get(s) != e || s.state != Section.QUEUED) {
                continue;
            }
            WorldRenderer r = s.owner;
            if (e.frame != frame) {
                long k = key(s, viewer, frame);
                e.frame = frame;
                if (k > e.key) {
                    e.key = k;
                    HEAP.add(e);
                    continue;
                }
                e.key = k;
            }
            if (RESORT_OUT.containsKey(s)) {
                pops--; // deferred pops do not count against MAX_POPS
                park(e, frame, 1); // resort outstanding: retried next frame
                continue;
            }
            if (gateChecks >= MAX_GATE_CHECKS) {
                HEAP.add(e);
                break;
            }
            gateChecks++;
            if (gateWaits(s, viewer, now)) {
                pops--; // deferred pops do not count against MAX_POPS
                park(e, frame, GATE_RECHECK_FRAMES);
                continue;
            }
            if (captures >= CAPTURES_PER_FRAME || (captures > 0 && now - t0 >= CAPTURE_NANOS)) {
                HEAP.add(e);
                break;
            }
            ENTRIES.remove(s);
            if (!submit(s, 1, false)) {
                break;
            }
            captures++;
            now = System.nanoTime();
        }
        if (captures > 0) {
            FrameStats.noteCaptures(captures, System.nanoTime() - t0);
        }
    }

    /** SCH-02: far sections wait up to 1.5 s for their 8 horizontal neighbour chunks (outer ring exempt). */
    private static boolean gateWaits(Section s, EntityLivingBase viewer, long now) {
        WorldRenderer r = s.owner;
        if (r.distanceToEntitySquared(viewer) <= GATE_DIST2 || r.worldObj == null) {
            return false;
        }
        int cx = (r.posXPlus - 8) >> 4, cz = (r.posZPlus - 8) >> 4;
        int vcx = ((int) Math.floor(viewer.posX)) >> 4, vcz = ((int) Math.floor(viewer.posZ)) >> 4;
        int ringX = Math.max(1, TerrainGrid.wide() / 2 - 1), ringZ = Math.max(1, TerrainGrid.deep() / 2 - 1);
        if (Math.abs(cx - vcx) >= ringX || Math.abs(cz - vcz) >= ringZ) {
            return false;
        }
        net.minecraft.IChunkProvider cp = r.worldObj.getChunkProvider();
        boolean all = true;
        for (int dz = -1; dz <= 1 && all; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dz != 0) && !cp.chunkExists(cx + dx, cz + dz)) {
                    all = false;
                    break;
                }
            }
        }
        if (all) {
            return false;
        }
        if (s.neighbourWaitSince == 0L) {
            s.neighbourWaitSince = now;
            return true;
        }
        return now - s.neighbourWaitSince < GATE_WAIT_NANOS;
    }

    /** Smaller = earlier: in-frustum, BFS-visible, distance, chunkIndex (MITE RenderSorter refined by visibility). */
    private static long key(Section s, EntityLivingBase viewer, int frame) {
        WorldRenderer r = s.owner;
        long k = r.isInFrustum ? 0L : (1L << 62);
        if (s.frameVisited != frame) {
            k |= 1L << 61;
        }
        float d = r.distanceToEntitySquared(viewer);
        long bits = Float.floatToIntBits(d < 0.0F ? 0.0F : d) & 0x7FFFFFFFL;
        return k | (bits << 14) | (r.chunkIndex & 0x3FFFL);
    }

    private static void enqueue(Section s) {
        s.state = Section.QUEUED;
        Entry e = new Entry(s);
        ENTRIES.put(s, e); // replaces (invalidates) any older heap entry of the section
        e.frame = -1;
        e.key = 0L; // refreshed on first pop; new entries are examined early
        HEAP.add(e);
    }

    /** Increments {@code Section.version}, marks DIRTY, queues once (coalescing); also sets {@code WorldRenderer.needsUpdate}. */
    static void markDirty(WorldRenderer r) {
        r.needsUpdate = true;
        Section s = TerrainGrid.sectionOf(r);
        if (s == null) {
            return;
        }
        s.version++;
        // an outstanding resort must never block the rebuild of an edited section
        RESORT_OUT.remove(s);
        s.resortPending = false;
        int nx = r.posXPlus - 8, ny = r.posYPlus - 8, nz = r.posZPlus - 8;
        if (nx != s.x || ny != s.y || nz != s.z) {
            // Ring move: the held geometry was built for the old origin, but TerrainDraw places it at s.x/y/z, so it
            // would show at the wrong place. Release it (MITE: skipRenderPass) so the section draws nothing until the
            // rebuild commits. Any in-flight result carries the old version and is rejected by handleResult/commit.
            net.xiaoyu233.fml.vulkan.render.gpu.GpuArena arena = SectionCommit.arena();
            for (int l = 0; l < Section.LAYERS; l++) {
                if (s.alloc[l] != null) {
                    arena.free(s.alloc[l]);
                    s.alloc[l] = null;
                }
                s.quads[l] = 0;
            }
            s.x = nx;
            s.y = ny;
            s.z = nz;
        }
        if ((s.state == Section.QUEUED && ENTRIES.containsKey(s)) || (s.state == Section.BUILDING && IN_FLIGHT.containsKey(s))) {
            return; // coalesced / stale result will requeue
        }
        // any other state (incl. a QUEUED/BUILDING section whose entry or task was dropped by a reset / epoch bump) is queued again
        s.state = Section.DIRTY;
        enqueue(s);
    }

    /** Called by loadRenderers / world change: drops every queued entry (epoch bumped by the mixin). */
    static void reset() {
        for (Section s : ENTRIES.keySet()) {
            s.state = Section.DIRTY;
        }
        for (Section s : IN_FLIGHT.keySet()) {
            s.state = Section.DIRTY;
        }
        for (Section s : RESORT_OUT.keySet()) {
            s.resortPending = false;
        }
        RESORT_OUT.clear();
        for (int i = 0; i < PENDING.size(); i++) {
            PENDING.get(i).result.release();
        }
        PENDING.clear();
        HEAP.clear();
        ENTRIES.clear();
        IN_FLIGHT.clear();
        NEAR_OUT.clear();
        PARKED.clear();
        GATED.clear();
        NEAR_LIST.clear();
        unavailableUntilFrame = -1;
        // results of the old epoch are released by MeshWorkers (epoch mismatch) or dropped in handleResult (not in flight)
    }

    /** Queued plus in-flight plus parked / gated sections: zero means the terrain is fully meshed for the current world state. */
    static int pendingTotal() {
        return ENTRIES.size() + IN_FLIGHT.size() + PARKED.size();
    }

    static String debugCounts() {
        return "entries=" + ENTRIES.size() + " inFlight=" + IN_FLIGHT.size() + " near=" + NEAR_OUT.size() + " parked=" + PARKED.size() + " gated=" + GATED.size()
                + " pending=" + PENDING.size() + " heap=" + HEAP.size();
    }

    static int queued() {
        return ENTRIES.size();
    }

    /** Render-thread fallback when mesh workers are unavailable. */
    private static final class SyncMesher {
        private static boolean logged;

        private SyncMesher() {
        }

        /**
         * Meshes the section on the calling thread; false (section left DIRTY) when no mesher is available. Never throws.
         * FOLLOW-UP (NR-10 dependency): a real render-thread mesher needs a MeshResult that can carry layer buffers, and
         * SectionSnapshot/MeshResult are NR-10 stubs that throw. Until NR-10 lands this always returns false, so terrain
         * stays empty and the NR-09-only testbot route cannot show terrain. Documented in the NR-09 report.
         */
        static boolean mesh(Section section) {
            if (!logged) {
                logged = true;
                VkLog.LOGGER.warn("SyncMesher: no render-thread mesher available until NR-10 lands; sections stay DIRTY");
            }
            return false;
        }
    }
}
