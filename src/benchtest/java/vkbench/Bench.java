package vkbench;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.EnumGameType;
import net.minecraft.GuiMainMenu;
import net.minecraft.Minecraft;
import net.minecraft.ServerPlayer;
import net.minecraft.WorldRenderer;
import net.minecraft.WorldServer;
import net.minecraft.WorldSettings;
import net.minecraft.WorldType;
import net.minecraft.server.MinecraftServer;

/**
 * Backend-agnostic traversal benchmark (7.3). The same classes run unchanged on the OpenGL baseline (commit 2041277), on the phase-1 emulation and on the native renderer:
 * only {@code net.minecraft.*} and FML APIs are referenced directly, everything backend specific is read reflectively by {@link BenchMetrics} and reads as -1 when absent.
 * <p>
 * Route (four dimensions, fixed seed, creative flying player, poses keyed by elapsed client TICKS so every build flies the identical path): see {@link #routeFor}. A run of
 * the whole route is one <i>pass</i>; the warm pass is not measured (pipeline caches, chunk generation on disk), then {@code fml.bench.runs} measured passes follow. Output
 * under {@code <docs>/perf/bench-<tag>/}: {@code bench-<build>-<dim>-<rd>-<run>-frames.csv} per dimension and run, {@code summary.csv} (one row per run and segment) and
 * {@code summary.md} (median and min-max over the runs, environment).
 * <p>
 * Properties: {@code fml.bench=true} (activates), {@code .tag}, {@code .build} (label of the compared build: baseline|native|phase1|variant name), {@code .docs} (docs dir),
 * {@code .rd} (normal|far), {@code .runs} (default 3), {@code .warm} (default true), {@code .dims} (comma list of overworld,tf,underworld,nether), {@code .scale} (shrinks
 * every duration, 1.0 = full route; 0.05 = smoke), {@code .viewDistance} (server view distance), {@code .keepWorld}.
 */
public final class Bench {
    public static final boolean ENABLED = Boolean.getBoolean("fml.bench");
    public static final String PREFIX = "[VkBench] ";
    public static final String TAG = System.getProperty("fml.bench.tag", "run");
    public static final String BUILD = System.getProperty("fml.bench.build", "native");
    static final long SEED = 424242L;
    static final String WORLD = "vkbench";

    private static volatile Bench instance;
    private static volatile long frameStartNs;
    private static volatile boolean frameHooksLive;

    final Minecraft mc;
    final File outDir;
    final BenchMetrics metrics = new BenchMetrics();
    private final Queue<Task<?>> clientTasks = new ConcurrentLinkedQueue<>();
    private final Queue<Task<?>> serverTasks = new ConcurrentLinkedQueue<>();
    private volatile long ticks;
    private volatile boolean aborted;
    private Thread thread;

    // route follower (client thread)
    private volatile BenchRoute.Path path;
    private volatile int pathTick;
    private volatile boolean pathDone;

    // frame recorder (render thread)
    private volatile BenchStats recording;
    private BufferedWriter csv;
    /** One row per frame handed to the display (native: RenderCore.presentTime; baseline: every frame end, GL swaps each frame). */
    private BufferedWriter presentsCsv;
    private long presentIdx = -1;
    private final StringBuilder row = new StringBuilder(256);
    private long lastFrameEndNs;
    private long t0Ns;
    private int lastChunkUpdates;
    private long lastCommits = -1;
    private final List<String[]> summaryRows = new ArrayList<>();
    private final List<BenchStats.Summary> summaries = new ArrayList<>();
    private final List<String> summaryKeys = new ArrayList<>();

    private Bench(Minecraft mc) {
        this.mc = mc;
        File docs = new File(System.getProperty("fml.bench.docs", "../docs"));
        this.outDir = new File(new File(docs, "perf"), "bench-" + TAG);
        outDir.mkdirs();
    }

    static void log(String msg) {
        System.out.println(PREFIX + msg);
    }

    // ------------------------------------------------------------------ hooks (game threads)

    public static void onClientTick(Minecraft mc) {
        if (!ENABLED) {
            return;
        }
        Bench b = instance;
        if (b == null) {
            synchronized (Bench.class) {
                if (instance == null) {
                    instance = new Bench(mc);
                    instance.start();
                }
                b = instance;
            }
        }
        b.ticks++;
        b.followPath();
        b.drain(b.clientTasks);
    }

    public static void onServerTick() {
        Bench b = instance;
        if (b != null) {
            b.drain(b.serverTasks);
        }
    }

    public static void onFrameStart() {
        if (frameHooksLive) {
            frameStartNs = System.nanoTime();
        }
    }

    public static void onFrameEnd(Minecraft mc) {
        Bench b = instance;
        if (b == null) {
            return;
        }
        frameHooksLive = true;
        b.recordFrame(mc);
    }

    private void drain(Queue<Task<?>> q) {
        Task<?> t;
        while ((t = q.poll()) != null) {
            t.execute();
        }
    }

    // ------------------------------------------------------------------ camera path (client thread, once per tick)

    private final double[] pose = new double[5];

    private void followPath() {
        BenchRoute.Path p = path;
        if (p == null || pathDone || mc.thePlayer == null) {
            return;
        }
        int t = pathTick;
        if (t >= p.ticks()) {
            pathDone = true;
            return;
        }
        p.pose(t, pose);
        net.minecraft.EntityClientPlayerMP pl = mc.thePlayer;
        double ox = pl.posX;
        double oy = pl.posY;
        double oz = pl.posZ;
        pl.setPositionAndRotation(pose[0], pose[1] + pl.yOffset, pose[2], (float) pose[3], (float) pose[4]);
        if (t > 0) {
            pl.prevPosX = ox;
            pl.prevPosY = oy;
            pl.prevPosZ = oz;
            pl.lastTickPosX = ox;
            pl.lastTickPosY = oy;
            pl.lastTickPosZ = oz;
        }
        pl.motionX = 0;
        pl.motionY = 0;
        pl.motionZ = 0;
        pl.rotationYawHead = (float) pose[3];
        pl.prevRotationYawHead = (float) pose[3];
        pathTick = t + 1;
    }

    // ------------------------------------------------------------------ frame recorder (render thread)

    private void recordFrame(Minecraft mc) {
        long now = System.nanoTime();
        long prev = lastFrameEndNs;
        lastFrameEndNs = now;
        BenchStats rec = recording;
        if (prev == 0 || rec == null || csv == null) {
            presentIdx = PRESENT_COUNT == null ? -1 : presentCount();
            lastChunkUpdates = WorldRenderer.chunksUpdated;
            lastCommits = nativeCommits();
            return;
        }
        long frameNs = now - prev;
        long cpuNs = -1; // set from FrameStats.cpuNanos below (a wall time after present + limiter is not CPU render time)
        metrics.sample();
        cpuNs = metrics.cpuNanos;
        int cu = WorldRenderer.chunksUpdated;
        int dcu = cu >= lastChunkUpdates ? cu - lastChunkUpdates : cu;
        lastChunkUpdates = cu;
        long commits = nativeCommits();
        if (commits >= 0) {
            dcu = lastCommits >= 0 && commits >= lastCommits ? (int) (commits - lastCommits) : 0;
            lastCommits = commits;
        }
        rec.chunkUpdates += dcu;
        long heap = BenchMetrics.heapUsedMb();
        long rss = metrics.rssMb();
        long gcC = BenchMetrics.gcCount();
        long gcM = BenchMetrics.gcMillis();
        rec.add(frameNs / 1.0e6F, metrics.draws, metrics.gpuMs, heap, rss, metrics.vmaMb, gcC, gcM);
        StringBuilder r = row;
        r.setLength(0);
        r.append(now - t0Ns).append(',').append(frameNs).append(',').append(cpuNs).append(',').append(metrics.presentNanos).append(',');
        r.append(metrics.gpuMs < 0 ? "-1" : String.format(Locale.ROOT, "%.3f", metrics.gpuMs)).append(',').append(metrics.draws).append(',').append(metrics.encodedDraws).append(',');
        r.append(metrics.sectionsVisible).append(',').append(dcu).append(',').append(metrics.captureNanos).append(',').append(heap).append(',').append(rss).append(',');
        r.append(metrics.vmaMb).append(',').append(gcC).append(',').append(gcM).append(',').append(rec.segment).append(',').append(metrics.submitNanos).append(',').append(metrics.presented).append(',').append(metrics.fenceWaitNanos).append('\n');
        try {
            csv.write(r.toString());
            writePresents(now, rec);
        } catch (IOException e) {
            // disk full: the run goes on, the summary still has the numbers
        }
    }

    private static final java.lang.reflect.Method PRESENT_COUNT = coreMethod("presentCount");
    private static final java.lang.reflect.Method PRESENT_TIME = coreMethod("presentTime", long.class);
    private static final java.lang.reflect.Method PRESENTATION_NAME = coreMethod("presentationName");
    private static final java.lang.reflect.Method IMAGE_COUNT = coreMethod("swapchainImageCount");

    private static java.lang.reflect.Method coreMethod(String name, Class<?>... args) {
        try {
            return Class.forName("net.xiaoyu233.fml.vulkan.core.RenderCore").getMethod(name, args);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null; // OpenGL baseline
        }
    }

    private static long presentCount() {
        try {
            return ((Number) PRESENT_COUNT.invoke(null)).longValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    /** Presentation path actually used (native), e.g. "layer presenter (...)" / "swapchain IMMEDIATE"; null on the baseline. */
    static String presentationName() {
        try {
            return PRESENTATION_NAME == null ? null : (String) PRESENTATION_NAME.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static int swapchainImages() {
        try {
            return IMAGE_COUNT == null ? -1 : ((Number) IMAGE_COUNT.invoke(null)).intValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    /** Every present since the last frame end, with its own timestamp; the baseline presents exactly once per frame (at its end). */
    private void writePresents(long now, BenchStats rec) throws IOException {
        if (presentsCsv == null) {
            return;
        }
        if (PRESENT_COUNT == null) {
            presentsCsv.write(Long.toString(now - t0Ns));
            presentsCsv.write(',');
            presentsCsv.write(rec.segment);
            presentsCsv.write('\n');
            return;
        }
        long n = presentCount();
        if (presentIdx < 0) {
            presentIdx = n;
        }
        for (long i = presentIdx; i < n; i++) {
            long t;
            try {
                t = ((Number) PRESENT_TIME.invoke(null, i)).longValue();
            } catch (ReflectiveOperationException | RuntimeException e) {
                t = 0;
            }
            if (t != 0) {
                presentsCsv.write(Long.toString(t - t0Ns));
                presentsCsv.write(',');
                presentsCsv.write(rec.segment);
                presentsCsv.write('\n');
            }
        }
        presentIdx = n;
    }

    // ------------------------------------------------------------------ script thread

    private static final class Task<T> {
        final Callable<T> body;
        volatile boolean done;
        T result;
        Throwable error;

        Task(Callable<T> body) {
            this.body = body;
        }

        void execute() {
            try {
                result = body.call();
            } catch (Throwable t) {
                error = t;
            } finally {
                done = true;
            }
        }
    }

    private <T> T await(Queue<Task<?>> q, Callable<T> body, long timeoutMs, String what) throws Exception {
        if (aborted) {
            throw new IllegalStateException("bench aborted");
        }
        Task<T> task = new Task<>(body);
        q.add(task);
        long end = System.currentTimeMillis() + timeoutMs;
        while (!task.done) {
            if (System.currentTimeMillis() > end) {
                throw new java.util.concurrent.TimeoutException(what + " task not executed within " + timeoutMs + " ms");
            }
            Thread.sleep(2);
        }
        if (task.error != null) {
            if (task.error instanceof Exception e) {
                throw e;
            }
            throw new RuntimeException(task.error);
        }
        return task.result;
    }

    <T> T client(Callable<T> c) throws Exception {
        return await(clientTasks, c, 900_000, "client");
    }

    void client(Runnable r) throws Exception {
        client(() -> {
            r.run();
            return null;
        });
    }

    <T> T server(Callable<T> c) throws Exception {
        return await(serverTasks, c, 120_000, "server");
    }

    void server(Runnable r) throws Exception {
        server(() -> {
            r.run();
            return null;
        });
    }

    void waitTicks(int n) throws Exception {
        long target = ticks + n;
        long end = System.currentTimeMillis() + 120_000L + n * 400L;
        while (ticks < target) {
            if (aborted) {
                throw new IllegalStateException("bench aborted");
            }
            if (System.currentTimeMillis() > end) {
                throw new java.util.concurrent.TimeoutException("waited too long for " + n + " ticks");
            }
            Thread.sleep(2);
        }
    }

    private boolean waitFor(Callable<Boolean> cond, int maxTicks) throws Exception {
        for (int i = 0; i < maxTicks; i++) {
            Boolean b = client(cond);
            if (b != null && b) {
                return true;
            }
            waitTicks(1);
        }
        return false;
    }

    private void start() {
        thread = new Thread(() -> {
            try {
                String light = System.getProperty("fml.vulkan.lightScene");
                String water = System.getProperty("fml.vulkan.waterScene");
                if (water != null) {
                    new WaterScene(this, new java.io.File(water)).run();
                } else if (light != null) {
                    new LightScene(this, new java.io.File(light)).run();
                } else {
                    new BenchScript(this).run();
                }
            } catch (Throwable t) {
                t.printStackTrace();
                log("FATAL: script died: " + t);
            } finally {
                finish();
            }
        }, "VkBench-script");
        thread.setDaemon(true);
        thread.start();
    }

    void finish() {
        closeCsv();
        log("finished, output in " + outDir.getAbsolutePath() + ", exiting");
        try {
            client(() -> {
                mc.shutdown();
                return null;
            });
        } catch (Throwable ignored) {
            // shut down already
        }
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(25_000);
            } catch (InterruptedException ignored) {
                return;
            }
            Runtime.getRuntime().halt(0);
        }, "VkBench-killer");
        killer.setDaemon(true);
        killer.start();
    }

    // ------------------------------------------------------------------ segment API for the script

    /** Opens the frame CSV of (dimension, run). */
    void openCsv(String dim, String rd, String run) throws IOException {
        closeCsv();
        File f = new File(outDir, "bench-" + BUILD + "-" + dim + "-" + rd + "-" + run + "-frames.csv");
        csv = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8);
        csv.write("t_ns,frame_ns,cpu_render_ns,present_wait_ns,gpu_ms,draws,encoded_draws,sections_visible,sections_built,capture_ns,heap_used_mb,rss_mb,vma_mb,gc_count,gc_ms,segment,submit_ns,presented,fence_wait_ns\n");
        presentsCsv = Files.newBufferedWriter(new File(outDir, "bench-" + BUILD + "-" + dim + "-" + rd + "-" + run + "-presents.csv").toPath(), StandardCharsets.UTF_8);
        presentsCsv.write("t_ns,segment\n");
        presentIdx = -1;
        t0Ns = System.nanoTime();
        lastFrameEndNs = 0;
    }

    void closeCsv() {
        recording = null;
        try {
            if (csv != null) {
                csv.close();
            }
            if (presentsCsv != null) {
                presentsCsv.close();
            }
        } catch (IOException ignored) {
            // nothing to do
        }
        csv = null;
        presentsCsv = null;
    }

    /** Starts recording frames into the open CSV under a segment name; returns the stats object that fills up. */
    BenchStats beginSegment(String name) {
        BenchStats s = new BenchStats(name);
        recording = s;
        return s;
    }

    void endSegment(BenchStats s) {
        recording = null;
        try {
            if (csv != null) {
                csv.flush();
            }
            if (presentsCsv != null) {
                presentsCsv.flush();
            }
        } catch (IOException ignored) {
            // see recordFrame
        }
    }

    /** Runs a camera path to its end (client ticks); frames are recorded into {@code stats} when it is not null. */
    void fly(BenchRoute.Path p, BenchStats stats) throws Exception {
        pathDone = false;
        pathTick = 0;
        path = p;
        long end = System.currentTimeMillis() + 60_000L + p.ticks() * 2_000L;
        while (!pathDone) {
            if (aborted) {
                throw new IllegalStateException("bench aborted");
            }
            if (System.currentTimeMillis() > end) {
                path = null;
                throw new java.util.concurrent.TimeoutException("path '" + p.describe() + "' did not finish");
            }
            Thread.sleep(5);
        }
        path = null;
    }

    /** Starts a camera path without waiting (scene captures). */
    void startPath(BenchRoute.Path p) {
        pathDone = false;
        pathTick = 0;
        path = p;
    }

    void stopPath() {
        path = null;
    }

    boolean waitForCondition(Callable<Boolean> cond, int maxTicks) throws Exception {
        return waitFor(cond, maxTicks);
    }

    long tickCount() {
        return ticks;
    }

    private static final java.lang.reflect.Method NATIVE_PENDING = terrainMethod("pendingSections");
    private static final java.lang.reflect.Method NATIVE_COMMITS = terrainMethod("sectionCommits");

    private static java.lang.reflect.Method terrainMethod(String name) {
        try {
            return Class.forName("net.xiaoyu233.fml.vulkan.render.terrain.TerrainRenderer").getMethod(name);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** Monotonic section commit counter of the native terrain (contract request TerrainRenderer.sectionCommits), -1 when absent. */
    static long nativeCommits() {
        try {
            return NATIVE_COMMITS == null ? -1 : ((Number) NATIVE_COMMITS.invoke(null)).longValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * Number of rebuilds the renderer has queued or in flight: the native counter (TerrainRenderer.pendingSections) when the build has it, else the phase-1
     * list. -1 = unknown, which the settle watcher treats as not settled (the summary then shows -1, never a fake time).
     */
    int pendingRebuilds() {
        if (NATIVE_PENDING != null) {
            try {
                return ((Number) NATIVE_PENDING.invoke(null)).intValue();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return -1;
            }
        }
        try {
            Object rg = mc.renderGlobal;
            if (rg == null) {
                return -1;
            }
            for (Class<?> c = rg.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField("worldRenderersToUpdate");
                    f.setAccessible(true);
                    return ((List<?>) f.get(rg)).size();
                } catch (NoSuchFieldException e) {
                    // superclass
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
        return -1;
    }

    /** Result bookkeeping for the summary. */
    void addSummary(String key, BenchStats.Summary s) {
        summaryKeys.add(key);
        summaries.add(s);
    }

    List<String> summaryKeys() {
        return summaryKeys;
    }

    List<BenchStats.Summary> summaries() {
        return summaries;
    }

    // ------------------------------------------------------------------ world helpers

    void createWorld(boolean keep) throws Exception {
        createWorld(keep, WORLD);
    }

    void createWorld(boolean keep, String WORLD) throws Exception {
        client(() -> {
            WorldSettings ws = null;
            if (keep && mc.getSaveLoader().getWorldInfo(WORLD) != null) {
                // saved world loaded unchanged
            } else {
                mc.getSaveLoader().deleteWorldDirectory(WORLD);
                ws = new WorldSettings(SEED, EnumGameType.CREATIVE, true, false, WorldType.DEFAULT, false).enableCommands();
            }
            mc.launchIntegratedServer(WORLD, WORLD, ws);
            return null;
        });
    }

    ServerPlayer serverPlayer() {
        return (ServerPlayer) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
    }

    WorldServer serverWorld(int dimension) {
        return MinecraftServer.getServer().worldServerForDimension(dimension);
    }

    boolean mainMenuUp() {
        return mc.theWorld == null && mc.currentScreen instanceof GuiMainMenu;
    }
}
