package net.xiaoyu233.fml.vulkan.render.screen;

import net.xiaoyu233.fml.vulkan.VkLog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Worker side of screenshots: BufferedImage + PNG on a daemon thread. The render thread only does one bulk copy of the frame into a
 * pooled direct buffer ({@link #submit}); the BGRA-to-RGB conversion, the row flip and the PNG encode run on the worker.
 */
public final class ScreenshotWriter {
    /** Free slots kept for reuse; more than this are dropped (the GC reclaims them). */
    private static final int MAX_FREE = 3;

    private static final ConcurrentLinkedQueue<String> FAILURES = new ConcurrentLinkedQueue<>();
    private static final Object POOL_LOCK = new Object();
    private static final ArrayDeque<ByteBuffer> FREE = new ArrayDeque<>();
    private static int poolBytes = -1;          // byte size of every slot in FREE / of the current frame size
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static volatile boolean hasFailure;
    private static ExecutorService worker;

    private ScreenshotWriter() {
    }

    private static synchronized ExecutorService worker() {
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Screenshot-Writer");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
        }
        return worker;
    }

    private static int frameBytes(int w, int h) {
        return Math.toIntExact((long) w * h * 4L);
    }

    private static ByteBuffer allocateSlot(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Pre-allocates the pool for a {@code w} x {@code h} frame so that the first {@link #submit} does not allocate on the render thread.
     * Call it from outside the readback callback (for example from {@code Screenshots.capture}).
     */
    public static void prepare(int w, int h) {
        int bytes = frameBytes(w, h);
        synchronized (POOL_LOCK) {
            if (bytes != poolBytes) {
                FREE.clear();
                poolBytes = bytes;
            }
            while (FREE.size() < 2) {
                FREE.addLast(allocateSlot(bytes));
            }
        }
    }

    /** A free slot of {@code bytes} bytes; allocates only when the pool has none (first use, resize, or more shots in flight than slots). */
    private static ByteBuffer acquire(int bytes) {
        synchronized (POOL_LOCK) {
            if (bytes != poolBytes) {
                FREE.clear();
                poolBytes = bytes;
            }
            ByteBuffer slot = FREE.pollFirst();
            if (slot != null) {
                return slot;
            }
        }
        return allocateSlot(bytes);
    }

    private static void release(ByteBuffer slot) {
        synchronized (POOL_LOCK) {
            if (slot.capacity() == poolBytes && FREE.size() < MAX_FREE) {
                FREE.addLast(slot);
            }
        }
    }

    /**
     * Copies {@code bgraBottomUp} (w*h*4 bytes, valid only during the call) into a pooled direct buffer with one bulk copy and queues the
     * encode. The render thread does no per-pixel work; the conversion and the flip run on the worker.
     */
    public static void submit(ByteBuffer bgraBottomUp, int w, int h, File out) {
        int bytes = frameBytes(w, h);
        ByteBuffer slot = acquire(bytes);
        try {
            ByteBuffer src = bgraBottomUp.duplicate();
            src.clear().limit(bytes);
            slot.clear();
            slot.put(src);
            slot.clear();
        } catch (RuntimeException e) {
            release(slot);
            Screenshots.release(out);
            fail(e);
            return;
        }
        PENDING.incrementAndGet();
        try {
            worker().execute(() -> encode(slot, w, h, out));
        } catch (RuntimeException e) {
            PENDING.decrementAndGet();
            release(slot);
            Screenshots.release(out);
            fail(e);
        }
    }

    private static void encode(ByteBuffer slot, int w, int h, File out) {
        try {
            BufferedImage img = toImage(slot, w, h);
            ImageIO.write(img, "png", out);
        } catch (Throwable e) {
            fail(e);
        } finally {
            Screenshots.release(out);
            PENDING.decrementAndGet();
        }
    }

    /** Converts the bottom-up little-endian BGRA slot into a top-down TYPE_INT_RGB image, then returns the slot to the pool. */
    private static BufferedImage toImage(ByteBuffer slot, int w, int h) {
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            int[] dst = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            IntBuffer px = slot.clear().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            for (int y = 0; y < h; y++) {
                px.position((h - 1 - y) * w);
                px.get(dst, y * w, w);
            }
            return img;
        } finally {
            release(slot);
        }
    }

    static void fail(Throwable e) {
        VkLog.LOGGER.error("Screenshot failed", e);
        FAILURES.add("Failed to save: " + e);
        hasFailure = true;
    }

    /** A failure message for chat ("Failed to save: ..."), or null; polled on the render thread each tick. */
    public static String pollFailure() {
        if (!hasFailure) {
            return null;
        }
        String s = FAILURES.poll();
        if (s == null) {
            hasFailure = false;
        }
        return s;
    }

    /** Number of queued/running encodes (test harness). */
    public static int pending() {
        return PENDING.get();
    }

    /** Blocks until all queued encodes finished (test harness only). */
    public static void drainForTest() {
        while (PENDING.get() > 0) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
