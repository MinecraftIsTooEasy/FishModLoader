package vkbench;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Per frame readings that exist on only some builds. Backend agnostic by construction: everything outside {@code net.minecraft} / the JDK is reached reflectively and an
 * unavailable value reads as -1 (the CSV then holds -1 and the summary prints "n/a"), so the same bench mod runs unchanged on the OpenGL baseline, on the phase-1
 * emulation and on the native renderer ({@code FrameStats}, {@code RenderCore}, {@code CommandRecorder} are looked up by name once).
 */
final class BenchMetrics {
    private static final List<GarbageCollectorMXBean> GC = ManagementFactory.getGarbageCollectorMXBeans();

    private final Method statsLatest = method("net.xiaoyu233.fml.vulkan.render.frame.FrameStats", "latest");
    private final Method vma = method("net.xiaoyu233.fml.vulkan.core.RenderCore", "debugVmaBytes");
    private final Method recorderStats = method("net.xiaoyu233.fml.vulkan.pipeline.CommandRecorder", "currentStats");
    private Method[] frameAccessors;
    private Class<?> frameClass;
    private Method drawCallsAccessor;
    private volatile long rssMb = -1;

    /** FrameStats values of the last frame; -1 = not available. */
    long cpuNanos = -1;
    long presentNanos = -1;
    double gpuMs = -1;
    long draws = -1;
    long encodedDraws = -1;
    long sectionsVisible = -1;
    long captureNanos = -1;
    long submitNanos = -1;
    long presented = -1;
    long fenceWaitNanos = -1;
    long vmaMb = -1;

    BenchMetrics() {
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
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "VkBench-rss");
        sampler.setDaemon(true);
        sampler.start();
    }

    private static Method method(String cls, String name) {
        try {
            return Class.forName(cls).getMethod(name);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** Reads all optional values (render thread, once per frame). */
    void sample() {
        cpuNanos = -1;
        presentNanos = -1;
        gpuMs = -1;
        draws = -1;
        encodedDraws = -1;
        sectionsVisible = -1;
        captureNanos = -1;
        submitNanos = -1;
        presented = -1;
        fenceWaitNanos = -1;
        if (statsLatest != null) {
            try {
                Object frame = statsLatest.invoke(null);
                if (frame != null) {
                    if (frameAccessors == null || frameClass != frame.getClass()) {
                        frameClass = frame.getClass();
                        frameAccessors = new Method[] {accessor(frame, "cpuNanos"), accessor(frame, "presentNanos"), accessor(frame, "gpuMs"), accessor(frame, "draws"),
                            accessor(frame, "encodedDraws"), accessor(frame, "sectionsVisible"), accessor(frame, "captureNanos"), accessor(frame, "submitNanos"), accessor(frame, "presented"), accessor(frame, "fenceWaitNanos")};
                    }
                    cpuNanos = (long) num(frame, frameAccessors[0]);
                    presentNanos = (long) num(frame, frameAccessors[1]);
                    gpuMs = num(frame, frameAccessors[2]);
                    draws = (long) num(frame, frameAccessors[3]);
                    encodedDraws = (long) num(frame, frameAccessors[4]);
                    sectionsVisible = (long) num(frame, frameAccessors[5]);
                    captureNanos = (long) num(frame, frameAccessors[6]);
                    submitNanos = (long) num(frame, frameAccessors[7]);
                    fenceWaitNanos = (long) num(frame, frameAccessors[9]);
                    if (frameAccessors[8] != null) {
                        presented = Boolean.TRUE.equals(frameAccessors[8].invoke(frame)) ? 1 : 0;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // FrameStats changed shape: values stay -1
            }
        }
        if (draws < 0 && recorderStats != null) {
            try {
                Object st = recorderStats.invoke(null);
                if (drawCallsAccessor == null) {
                    drawCallsAccessor = st.getClass().getMethod("drawCalls");
                }
                draws = ((Number) drawCallsAccessor.invoke(st)).longValue();
            } catch (ReflectiveOperationException | RuntimeException e) {
                // phase-1 recorder not present (OpenGL baseline)
            }
        }
        vmaMb = -1;
        if (vma != null) {
            try {
                long b = ((Number) vma.invoke(null)).longValue();
                vmaMb = b < 0 ? -1 : b >> 20;
            } catch (ReflectiveOperationException | RuntimeException e) {
                // not initialised
            }
        }
    }

    private static Method accessor(Object frame, String name) {
        try {
            return frame.getClass().getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static double num(Object frame, Method m) throws ReflectiveOperationException {
        if (m == null) {
            return -1;
        }
        Object v = m.invoke(frame);
        return v instanceof Number n ? n.doubleValue() : -1;
    }

    long rssMb() {
        return rssMb;
    }

    static long gcCount() {
        long c = 0;
        for (GarbageCollectorMXBean b : GC) {
            c += Math.max(0, b.getCollectionCount());
        }
        return c;
    }

    static long gcMillis() {
        long c = 0;
        for (GarbageCollectorMXBean b : GC) {
            c += Math.max(0, b.getCollectionTime());
        }
        return c;
    }

    static long heapUsedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) >> 20;
    }
}
