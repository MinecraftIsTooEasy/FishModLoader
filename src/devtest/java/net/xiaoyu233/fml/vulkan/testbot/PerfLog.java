package net.xiaoyu233.fml.vulkan.testbot;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.Minecraft;
import net.minecraft.WorldRenderer;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.pipeline.CommandRecorder;

/**
 * Per frame CSV (frame time, draw calls, chunk updates, renderers waiting for a rebuild, Java heap, the current "leg" of the script) and a per second CSV
 * (fps, p99 / max frame time, stalls above 50 ms, VMA bytes, process RSS, GC counters, player position), both under {@code <docs>/perf/} (outside the
 * repository). Called from the end of every game loop iteration on the render thread; everything that costs more than a few field reads happens
 * at most once per second.
 * <p>
 * Chunk updates: MITE's {@code WorldRenderer.chunksUpdated} is reset to 0 by the game loop once per second, so the per frame value is the positive delta
 * (a smaller value than the previous sample means a reset happened in between and the current value is the delta).
 */
final class PerfLog {
    private static final float STALL_MS = 50.0F;

    private final Minecraft mc;
    private final BufferedWriter frameOut;
    private final BufferedWriter secOut;
    private final long startNs = System.nanoTime();
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private volatile String leg = "-";
    private volatile long rssMb = -1;

    private long lastNs;
    private int lastChunkUpdates;
    private long lastCommits = -1;
    private volatile long chunkUpdatesTotal;
    private volatile int quietRun;
    private long frameRows;
    private Field toUpdateField;
    private Field loadedField;
    private boolean fieldsResolved;
    private Object fieldsOwner;

    // current second
    private long secStartNs;
    private int secFrames;
    private double secSumMs;
    private float secMaxMs;
    private int secStalls;
    private long secDraws;
    private int secChunkUpdates;
    private int secPendingMax;
    private long secHeapMin = Long.MAX_VALUE;
    private long secHeapMax;
    private float[] secMs = new float[2048];
    private int secSeconds;
    private final List<long[]> memHistory = java.util.Collections.synchronizedList(new ArrayList<>());

    /** per leg aggregation for the report */
    private final Map<String, Leg> legs = java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    private static final class Leg {
        final String name;
        float[] ms = new float[4096];
        int n;
        double sumMs;
        float maxMs;
        int stalls;
        long draws;
        long chunkUpdates;
        int pendingMax;
        long heapMax;
        long firstFrame = -1;

        Leg(String name) {
            this.name = name;
        }

        synchronized void add(float frameMs, int drawCalls, int chunkUpd, int pending, long heapMb, long frame) {
            if (n == ms.length) {
                ms = Arrays.copyOf(ms, n * 2);
            }
            ms[n++] = frameMs;
            sumMs += frameMs;
            maxMs = Math.max(maxMs, frameMs);
            if (frameMs > STALL_MS) {
                stalls++;
            }
            draws += drawCalls;
            chunkUpdates += chunkUpd;
            pendingMax = Math.max(pendingMax, pending);
            heapMax = Math.max(heapMax, heapMb);
            if (firstFrame < 0) {
                firstFrame = frame;
            }
        }
    }

    PerfLog(File dir, String tag, Minecraft mc) {
        this.mc = mc;
        BufferedWriter f = null;
        BufferedWriter s = null;
        try {
            Files.createDirectories(dir.toPath());
            f = Files.newBufferedWriter(new File(dir, tag + "-frames.csv").toPath(), StandardCharsets.UTF_8);
            f.write("frame,t_ms,frame_ms,draws,chunk_updates,pending_updates,heap_mb,leg\n");
            s = Files.newBufferedWriter(new File(dir, tag + "-seconds.csv").toPath(), StandardCharsets.UTF_8);
            s.write("sec,t_s,frames,fps,frame_ms_avg,frame_ms_p99,frame_ms_max,stalls_50ms,draws_avg,chunk_updates,pending_max,loaded_renderers,heap_min_mb,heap_max_mb,"
                + "gc_count,gc_ms,vma_mb,static_buffers,rss_mb,x,y,z,leg\n");
        } catch (IOException e) {
            System.err.println(TestBot.PREFIX + "cannot open the perf CSV files in " + dir + ": " + e);
        }
        this.frameOut = f;
        this.secOut = s;
        Thread sampler = new Thread(() -> {
            long pid = ProcessHandle.current().pid();
            while (true) {
                try {
                    Process p = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(pid)).redirectErrorStream(true).start();
                    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                    p.waitFor();
                    rssMb = Long.parseLong(out) >> 10;
                } catch (Throwable t) {
                    rssMb = -1;
                }
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "VkTestBot-rss");
        sampler.setDaemon(true);
        sampler.start();
    }

    /** The label of the script phase the following frames belong to (written to every CSV row, grouped in the leg table). */
    void setLeg(String name) {
        this.leg = name == null || name.isEmpty() ? "-" : name.replace(',', '_');
    }

    String leg() {
        return leg;
    }

    private void resolveFields(Minecraft mc) {
        if (fieldsResolved && fieldsOwner == mc.renderGlobal) {
            return;
        }
        fieldsResolved = true;
        fieldsOwner = mc.renderGlobal;
        toUpdateField = null;
        loadedField = null;
        if (mc.renderGlobal == null) {
            return;
        }
        for (Class<?> c = mc.renderGlobal.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    if (f.getName().equals("worldRenderersToUpdate") && List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        toUpdateField = f;
                    } else if (f.getName().equals("renderersLoaded") && f.getType() == int.class) {
                        f.setAccessible(true);
                        loadedField = f;
                    }
                } catch (RuntimeException ignored) {
                    // inaccessible: the column stays -1
                }
            }
        }
    }

    /** Chunk meshes (16x16x16 renderers) rebuilt since the start of the run. */
    long chunkUpdatesTotal() {
        return chunkUpdatesTotal;
    }

    /** Consecutive presented frames (up to now) in which no chunk mesh was rebuilt; frame accurate, unlike polling from the tick. */
    int quietFrameRun() {
        return quietRun;
    }

    /** Renderers (16x16x16 chunk meshes) queued for a rebuild; -1 when unknown. */
    int pendingUpdates(Minecraft mc) {
        if (TerrainSignals.nativeCounters()) {
            return TerrainSignals.nativePending();
        }
        resolveFields(mc);
        if (toUpdateField == null) {
            return -1;
        }
        try {
            List<?> l = (List<?>) toUpdateField.get(mc.renderGlobal);
            return l == null ? 0 : l.size();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    int loadedRenderers(Minecraft mc) {
        resolveFields(mc);
        if (loadedField == null) {
            return -1;
        }
        try {
            return loadedField.getInt(mc.renderGlobal);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    /** Called on the render thread at the end of each game loop iteration. */
    void onFrame(Minecraft mc) {
        long now = System.nanoTime();
        if (lastNs == 0) {
            lastNs = now;
            secStartNs = now;
            return;
        }
        float ms = (now - lastNs) / 1.0e6F;
        lastNs = now;
        int draws = CommandRecorder.currentStats().drawCalls();
        int cu = WorldRenderer.chunksUpdated;
        int dcu = cu >= lastChunkUpdates ? cu - lastChunkUpdates : cu;
        lastChunkUpdates = cu;
        long commits = TerrainSignals.nativeCommits();
        if (commits >= 0) {
            // native terrain: the phase-1 static no longer moves, the commit counter does
            dcu = lastCommits >= 0 && commits >= lastCommits ? (int) (commits - lastCommits) : 0;
            lastCommits = commits;
        }
        chunkUpdatesTotal += dcu;
        quietRun = dcu == 0 ? quietRun + 1 : 0;
        int pending = pendingUpdates(mc);
        Runtime rt = Runtime.getRuntime();
        long heapMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
        long frame = RenderCore.frameNumber();

        try {
            if (frameOut != null) {
                frameOut.write(Long.toString(frame));
                frameOut.write(',');
                frameOut.write(Long.toString((now - startNs) / 1_000_000L));
                frameOut.write(',');
                frameOut.write(String.format(Locale.ROOT, "%.2f", ms));
                frameOut.write(',');
                frameOut.write(Integer.toString(draws));
                frameOut.write(',');
                frameOut.write(Integer.toString(dcu));
                frameOut.write(',');
                frameOut.write(Integer.toString(pending));
                frameOut.write(',');
                frameOut.write(Long.toString(heapMb));
                frameOut.write(',');
                frameOut.write(leg);
                frameOut.write('\n');
                if ((++frameRows & 1023) == 0) {
                    frameOut.flush();
                }
            }
        } catch (IOException e) {
            // disk full or closed during shutdown: the run goes on
        }

        Leg l = legs.computeIfAbsent(leg, Leg::new);
        l.add(ms, draws, dcu, pending, heapMb, frame);

        if (secFrames == secMs.length) {
            secMs = Arrays.copyOf(secMs, secFrames * 2);
        }
        secMs[secFrames++] = ms;
        secSumMs += ms;
        secMaxMs = Math.max(secMaxMs, ms);
        if (ms > STALL_MS) {
            secStalls++;
        }
        secDraws += draws;
        secChunkUpdates += dcu;
        secPendingMax = Math.max(secPendingMax, pending);
        secHeapMin = Math.min(secHeapMin, heapMb);
        secHeapMax = Math.max(secHeapMax, heapMb);

        if (now - secStartNs >= 1_000_000_000L) {
            writeSecond(mc, now);
            secStartNs = now;
        }
    }

    private void writeSecond(Minecraft mc, long now) {
        float[] sorted = Arrays.copyOf(secMs, secFrames);
        Arrays.sort(sorted);
        float p99 = sorted[Math.min(secFrames - 1, (int) (secFrames * 0.99))];
        long gcCount = 0;
        long gcMs = 0;
        for (GarbageCollectorMXBean b : gcBeans) {
            gcCount += Math.max(0, b.getCollectionCount());
            gcMs += Math.max(0, b.getCollectionTime());
        }
        double x = 0, y = 0, z = 0;
        if (mc.thePlayer != null) {
            x = mc.thePlayer.posX;
            y = mc.thePlayer.boundingBox.minY;
            z = mc.thePlayer.posZ;
        }
        memHistory.add(new long[] {secHeapMin == Long.MAX_VALUE ? -1 : secHeapMin, secHeapMax, RenderCore.debugVmaBytes() >> 20, RenderCore.debugStaticBufferCount(), rssMb, gcMs});
        try {
            if (secOut != null) {
                secOut.write(String.format(Locale.ROOT, "%d,%.1f,%d,%.1f,%.2f,%.2f,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.1f,%.1f,%.1f,%s%n",
                    ++secSeconds, (now - startNs) / 1.0e9, secFrames, secFrames / ((now - secStartNs) / 1.0e9), secSumMs / secFrames, p99, secMaxMs, secStalls,
                    secDraws / secFrames, secChunkUpdates, secPendingMax, loadedRenderers(mc), secHeapMin == Long.MAX_VALUE ? -1 : secHeapMin, secHeapMax, gcCount, gcMs,
                    RenderCore.debugVmaBytes() >> 20, RenderCore.debugStaticBufferCount(), rssMb, x, y, z, leg));
                secOut.flush();
            }
        } catch (IOException e) {
            // see onFrame
        }
        secFrames = 0;
        secSumMs = 0;
        secMaxMs = 0;
        secStalls = 0;
        secDraws = 0;
        secChunkUpdates = 0;
        secPendingMax = 0;
        secHeapMin = Long.MAX_VALUE;
        secHeapMax = 0;
    }

    /** One row per leg: frames, average fps, p50 / p99 / max frame time, stalls above 50 ms, draws, chunk updates, longest rebuild queue, heap peak. */
    String legTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("| leg | frames | avg fps | p50 ms | p99 ms | max ms | stalls >50 ms | draws/frame | chunk updates | max queued rebuilds | heap peak MB |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        List<Leg> copy;
        synchronized (legs) {
            copy = new ArrayList<>(legs.values());
        }
        for (Leg l : copy) {
            synchronized (l) {
                if (l.n == 0) {
                    continue;
                }
                float[] sorted = Arrays.copyOf(l.ms, l.n);
                Arrays.sort(sorted);
                sb.append(String.format(Locale.ROOT, "| %s | %d | %.0f | %.1f | %.1f | %.1f | %d | %d | %d | %d | %d |%n", l.name, l.n, 1000.0 / (l.sumMs / l.n), sorted[l.n / 2],
                    sorted[Math.min(l.n - 1, (int) (l.n * 0.99))], l.maxMs, l.stalls, l.draws / l.n, l.chunkUpdates, l.pendingMax, l.heapMax));
            }
        }
        return sb.toString();
    }

    /** One row per minute of the run: lowest / highest used Java heap, VMA allocated MB, live static buffers, process RSS MB, cumulative GC milliseconds. */
    String memorySummary() {
        List<long[]> h;
        synchronized (memHistory) {
            h = new ArrayList<>(memHistory);
        }
        StringBuilder sb = new StringBuilder("| minute | heap floor MB | heap peak MB | VMA MB | static buffers | RSS MB | GC ms total |\n|---|---|---|---|---|---|---|\n");
        for (int m = 0; m * 60 < h.size(); m++) {
            long floor = Long.MAX_VALUE;
            long peak = 0;
            long[] last = null;
            for (int i = m * 60; i < Math.min(h.size(), m * 60 + 60); i++) {
                long[] r = h.get(i);
                if (r[0] >= 0) {
                    floor = Math.min(floor, r[0]);
                }
                peak = Math.max(peak, r[1]);
                last = r;
            }
            if (last != null) {
                sb.append(String.format(Locale.ROOT, "| %d | %d | %d | %d | %d | %d | %d |%n", m + 1, floor == Long.MAX_VALUE ? -1 : floor, peak, last[2], last[3], last[4], last[5]));
            }
        }
        return sb.toString();
    }

    void flush() {
        try {
            if (frameOut != null) {
                frameOut.flush();
            }
            if (secOut != null) {
                secOut.flush();
            }
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    void close() {
        flush();
    }
}
