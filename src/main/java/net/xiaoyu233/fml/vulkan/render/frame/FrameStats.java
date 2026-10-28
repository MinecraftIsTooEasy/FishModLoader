package net.xiaoyu233.fml.vulkan.render.frame;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Per-frame statistics ring (4.2, 5.10): the last {@value #RING} frames in primitive arrays (nothing is allocated per frame unless a
 * listener is registered, the {@link Frame} record is built on demand). {@link FramePacer#endFrame()} is the only writer; other
 * packages feed the counters of the frame being built through the {@code note*} methods (render thread, plain adds). The F3 overlay
 * line and the bench CSV read {@link #latest()} / {@link #overlayLine()}.
 */
public final class FrameStats {
    public record Frame(long index, long cpuNanos, long presentNanos, double gpuMs, int draws, int encodedDraws, int sectionsVisible,
                        long captureNanos, int captures, int inlineCopies, int pipelineMisses, int pipelinesCreatedRenderThread, long heapUsed,
                        long submitNanos, boolean presented, long fenceWaitNanos) {
    }

    public static final int RING = 1024;
    private static final int MASK = RING - 1;
    private static final long OVERLAY_REFRESH_NANOS = 250_000_000L;

    private static final long[] CPU = new long[RING];
    private static final long[] PRESENT = new long[RING];
    private static final double[] GPU = new double[RING];
    private static final int[] DRAWS = new int[RING];
    private static final int[] ENCODED = new int[RING];
    private static final int[] SECTIONS = new int[RING];
    private static final long[] CAPTURE_NANOS = new long[RING];
    private static final int[] CAPTURES = new int[RING];
    private static final int[] INLINE_COPIES = new int[RING];
    private static final int[] MISSES = new int[RING];
    private static final int[] CREATED = new int[RING];
    private static final long[] HEAP = new long[RING];
    private static final long[] POLL_AT = new long[RING];
    private static final long[] PRESENT_END_AT = new long[RING];
    /** vkQueueSubmit time of the frame (inside presentNanos), whether an image was presented, and the fence wait of nextFrame(). */
    private static final long[] SUBMIT = new long[RING];
    private static final boolean[] PRESENTED = new boolean[RING];
    private static final long[] FENCE_WAIT = new long[RING];

    // counters of the frame being built
    private static int curDraws;
    private static int curEncoded;
    private static int curSections;
    private static long curCaptureNanos;
    private static int curCaptures;
    private static int curInlineCopies;
    private static int curMisses;
    private static int curCreated;
    private static long curSubmitNanos;
    private static boolean curPresented;
    private static long curFenceWaitNanos;

    /** Number of frames recorded since start (the index of the next frame). */
    private static long count;
    private static Consumer<Frame>[] listeners = newListeners(0);
    private static Frame latestCache;
    private static long latestCacheIndex = -1L;
    private static String overlay = "vk: -";
    private static long overlayAt;

    private FrameStats() {
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Frame>[] newListeners(int n) {
        return (Consumer<Frame>[]) new Consumer[n];
    }

    // ---------------------------------------------------------------------------------------------------------
    // writers (render thread)
    // ---------------------------------------------------------------------------------------------------------

    /** Draw calls issued / encoded into command buffers by the frame being built (adds up until the frame ends). */
    public static void noteDraws(int draws, int encodedDraws) {
        curDraws += draws;
        curEncoded += encodedDraws;
    }

    /** Terrain sections drawn by the frame being built (last value wins). */
    public static void noteSections(int sectionsVisible) {
        curSections = sectionsVisible;
    }

    /** Far-set snapshot captures done by the frame being built and their time. */
    public static void noteCaptures(int captures, long nanos) {
        curCaptures += captures;
        curCaptureNanos += nanos;
    }

    /** Inline (in render pass) texture copies of the frame being built. */
    public static void noteInlineCopies(int n) {
        curInlineCopies += n;
    }

    /** Pipeline misses (fallback used) and pipelines created on the render thread during the frame being built. */
    public static void notePipelines(int misses, int createdOnRenderThread) {
        curMisses += misses;
        curCreated += createdOnRenderThread;
    }

    /**
     * Presentation timing of the frame being built (render thread, called by the core once per present): time spent in vkQueueSubmit,
     * whether an image was actually presented (false for a skipped / gated frame) and the fence wait of the next frame slot. Last value wins.
     */
    public static void noteFrameTiming(long submitNanos, boolean presented, long fenceWaitNanos) {
        curSubmitNanos = submitNanos;
        curPresented = presented;
        curFenceWaitNanos = fenceWaitNanos;
    }

    /** Called by {@link FramePacer#pollInput()}: the time (nanoTime) at which the frame being built sampled the input. */
    static void notePoll(long nanos) {
        POLL_AT[(int) (count & MASK)] = nanos;
    }

    /** Called by {@link FramePacer#endFrame()}: stores the frame and resets the counters. */
    static void record(long cpuNanos, long presentNanos, double gpuMs, long presentEndNanos) {
        int i = (int) (count & MASK);
        PRESENT_END_AT[i] = presentEndNanos;
        // keep POLL_AT of this frame for readers, but clear the slot of the frame that will reuse it a ring later
        POLL_AT[(int) ((count + 1) & MASK)] = 0L;
        CPU[i] = cpuNanos;
        PRESENT[i] = presentNanos;
        GPU[i] = gpuMs;
        DRAWS[i] = curDraws;
        ENCODED[i] = curEncoded;
        SECTIONS[i] = curSections;
        CAPTURE_NANOS[i] = curCaptureNanos;
        CAPTURES[i] = curCaptures;
        INLINE_COPIES[i] = curInlineCopies;
        MISSES[i] = curMisses;
        CREATED[i] = curCreated;
        SUBMIT[i] = curSubmitNanos;
        PRESENTED[i] = curPresented;
        FENCE_WAIT[i] = curFenceWaitNanos;
        Runtime rt = Runtime.getRuntime();
        HEAP[i] = rt.totalMemory() - rt.freeMemory();
        curDraws = curEncoded = curCaptures = curInlineCopies = curMisses = curCreated = 0;
        curCaptureNanos = 0L;
        curSections = 0;
        curSubmitNanos = 0L;
        curPresented = false;
        curFenceWaitNanos = 0L;
        long index = count++;
        Consumer<Frame>[] l = listeners;
        if (l.length > 0) {
            Frame f = at(index);
            for (Consumer<Frame> c : l) {
                try {
                    c.accept(f);
                } catch (RuntimeException e) {
                    net.xiaoyu233.fml.vulkan.VkLog.LOGGER.warn("FrameStats listener failed", e);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // readers
    // ---------------------------------------------------------------------------------------------------------

    /** The last recorded frame (an all-zero frame before the first). */
    public static Frame latest() {
        long n = count;
        if (n == 0) {
            return at(-1L);
        }
        if (latestCacheIndex != n - 1) {
            latestCache = at(n - 1);
            latestCacheIndex = n - 1;
        }
        return latestCache;
    }

    /** Frames recorded since start. */
    public static long frameCount() {
        return count;
    }

    /** nanoTime at which frame {@code index} sampled the input (0 when it did not poll or is out of the ring). */
    public static long pollNanos(long index) {
        return inRing(index) ? POLL_AT[(int) (index & MASK)] : 0L;
    }

    /** nanoTime at which frame {@code index} finished presenting (0 when out of the ring or not yet presented). */
    public static long presentEndNanos(long index) {
        return index < count && inRing(index) ? PRESENT_END_AT[(int) (index & MASK)] : 0L;
    }

    /** True while the frame being built (index {@link #frameCount()}) is the one whose poll time is {@link #pollNanos}. */
    private static boolean inRing(long index) {
        return index >= 0 && index > count - RING - 1 && index <= count;
    }

    /** The frame {@code framesAgo} frames before the latest (0 = latest); null when it is not (or no longer) in the ring. */
    public static Frame ago(int framesAgo) {
        long index = count - 1 - framesAgo;
        if (framesAgo < 0 || index < 0 || framesAgo >= RING) {
            return null;
        }
        return at(index);
    }

    public static void addListener(Consumer<Frame> l) {
        if (l == null) {
            return;
        }
        synchronized (FrameStats.class) {
            Consumer<Frame>[] old = listeners;
            Consumer<Frame>[] updated = Arrays.copyOf(old, old.length + 1);
            updated[old.length] = l;
            listeners = updated;
        }
    }

    public static void removeListener(Consumer<Frame> l) {
        synchronized (FrameStats.class) {
            Consumer<Frame>[] old = listeners;
            int k = -1;
            for (int i = 0; i < old.length; i++) {
                if (old[i] == l) {
                    k = i;
                    break;
                }
            }
            if (k < 0) {
                return;
            }
            Consumer<Frame>[] updated = newListeners(old.length - 1);
            System.arraycopy(old, 0, updated, 0, k);
            System.arraycopy(old, k + 1, updated, k, old.length - k - 1);
            listeners = updated;
        }
    }

    /**
     * The F3 overlay line {@code vk: <gpu ms> <draws> <sections>}; rebuilt four times per second at most, so calling it every frame
     * does not allocate.
     */
    public static String overlayLine() {
        long now = System.nanoTime();
        if (now - overlayAt >= OVERLAY_REFRESH_NANOS) {
            overlayAt = now;
            Frame f = latest();
            overlay = String.format(Locale.ROOT, "vk: %.2f ms %d draws %d sections", f.gpuMs(), f.draws(), f.sectionsVisible());
        }
        return overlay;
    }

    /** Clears the ring (tests). */
    static void reset() {
        count = 0;
        latestCacheIndex = -1L;
        curDraws = curEncoded = curCaptures = curInlineCopies = curMisses = curCreated = curSections = 0;
        curCaptureNanos = 0L;
        curSubmitNanos = 0L;
        curPresented = false;
        curFenceWaitNanos = 0L;
    }

    private static Frame at(long index) {
        if (index < 0) {
            return new Frame(0L, 0L, 0L, 0.0, 0, 0, 0, 0L, 0, 0, 0, 0, 0L, 0L, false, 0L);
        }
        int i = (int) (index & MASK);
        return new Frame(index, CPU[i], PRESENT[i], GPU[i], DRAWS[i], ENCODED[i], SECTIONS[i], CAPTURE_NANOS[i], CAPTURES[i],
                INLINE_COPIES[i], MISSES[i], CREATED[i], HEAP[i], SUBMIT[i], PRESENTED[i], FENCE_WAIT[i]);
    }

    /**
     * Sums over the frames {@code [fromIndex, frameCount())} still in the ring, without allocating: {@code l} = {frames, presented
     * frames, cpu nanos, frames with a GPU time}, {@code d[0]} = sum of the GPU milliseconds of those frames. Render thread (F3 overlay).
     */
    public static void summarize(long fromIndex, long[] l, double[] d) {
        long n = count;
        long from = Math.max(Math.max(fromIndex, 0L), n - RING);
        long frames = 0L;
        long presented = 0L;
        long cpu = 0L;
        long gpuFrames = 0L;
        double gpu = 0.0;
        for (long k = from; k < n; k++) {
            int i = (int) (k & MASK);
            frames++;
            if (PRESENTED[i]) {
                presented++;
            }
            cpu += CPU[i];
            if (GPU[i] > 0.0) {
                gpuFrames++;
                gpu += GPU[i];
            }
        }
        l[0] = frames;
        l[1] = presented;
        l[2] = cpu;
        l[3] = gpuFrames;
        d[0] = gpu;
    }
}
