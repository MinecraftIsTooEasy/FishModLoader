package net.xiaoyu233.fml.vulkan.window;

import java.util.Locale;

/**
 * The frame limiter behind {@code Display.sync(fps)}: waits until the end of the frame slot, sleeping in 1 ms slices while
 * there is time to spare and spinning the last stretch. One long sleep is not an option, the timers of the OS wake up late
 * by an amount that grows with the length of the sleep (measured on macOS: a 1 ms sleep takes 1.5 ms, a 5 ms sleep 7.5 ms
 * and a 30 ms sleep 40 ms).
 *
 * <p>How early the sleeping has to stop depends on how late a 1 ms sleep can come back on this system, so that is measured:
 * the longest recent {@code Thread.sleep(1)} ({@link #sleepPeak}) is the margin. It grows quickly when a sleep took longer
 * (by at most {@link #MAX_ATTACK} per sleep, so that one stall does not make the limiter spin for seconds) and shrinks by
 * {@link #DECAY} per frame, whether the frame slept or not. On a coarse timer (a Windows without a 1 ms timer resolution)
 * the margin ends up as long as the timer tick and the limiter only spins, like LWJGL 2 did.
 *
 * <p>The schedule does not drift (the next slot starts where this one was due to end), a hitch of more than two frames
 * restarts it. Not thread safe, like everything that runs the frame loop.
 */
public final class FrameLimiter {
    /** Time source and sleeper, replaceable to test the algorithm without waiting. */
    interface Clock {
        long nanoTime();

        void sleepOneMillisecond() throws InterruptedException;

        void spinWait();
    }

    private static final Clock SYSTEM = new Clock() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public void sleepOneMillisecond() throws InterruptedException {
            Thread.sleep(1L);
        }

        @Override
        public void spinWait() {
            Thread.onSpinWait();
        }
    };

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    /** The sleep slice, and the shortest time a slice can come back after. */
    private static final long SLICE = 1_000_000L;
    /** Margin on top of the longest recent sleep: the clock and the wake up are not exact. */
    private static final long SAFETY = 250_000L;
    private static final long MIN_PEAK = 1_250_000L;
    private static final long INITIAL_PEAK = 2_000_000L;
    /** Above the 15.6 ms tick of a Windows timer that was never set to 1 ms. */
    private static final long MAX_PEAK = 20_000_000L;
    private static final long MAX_ATTACK = 4_000_000L;
    private static final long DECAY = 20_000L;

    private static final FrameLimiter SHARED = new FrameLimiter(SYSTEM);

    static {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            startTimerAccuracyThread();
        }
    }

    private final Clock clock;
    private boolean started;
    private int fps;
    /** When the current frame slot started (nanoTime). */
    private long slotStart;
    /** Longest recent duration of a 1 ms sleep (nanoseconds). */
    private long sleepPeak = INITIAL_PEAK;

    FrameLimiter(Clock clock) {
        this.clock = clock;
    }

    /** {@code Display.sync}: returns when the frame slot of {@code fps} frames per second ends; nothing for fps <= 0. */
    public static void sync(int fps) {
        SHARED.limit(fps);
    }

    /**
     * Windows: the JVM's sleep is only as precise as the system timer, 15.6 ms unless a program asks for 1 ms. A thread that
     * sleeps "forever" is what makes the JVM ask (the trick LWJGL 2 uses for its {@code Sys}/{@code Display.sync}); where it
     * does not help it does no harm either.
     */
    private static void startTimerAccuracyThread() {
        try {
            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException ignored) {
                    // the thread is only there to sleep
                }
            }, "FML Timer Accuracy");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable ignored) {
            // the limiter works without it
        }
    }

    void limit(int newFps) {
        if (newFps <= 0) {
            return;
        }
        long now = clock.nanoTime();
        if (!started || newFps != fps) {
            started = true;
            fps = newFps;
            slotStart = now; // first frame (or another rate): the slot starts now, nothing to wait for yet
            return;
        }
        long frame = NANOS_PER_SECOND / newFps;
        long target = slotStart + frame;
        waitUntil(target, now);
        slotStart = target;
        long end = clock.nanoTime();
        if (end - slotStart > frame * 2) {
            slotStart = end; // far behind (hitch): do not try to catch up
        }
    }

    private void waitUntil(long target, long now) {
        try {
            while (target - now > sleepPeak + SAFETY) {
                clock.sleepOneMillisecond();
                long after = clock.nanoTime();
                long took = after - now;
                if (took > sleepPeak) {
                    sleepPeak = Math.min(MAX_PEAK, Math.min(took, sleepPeak + MAX_ATTACK));
                }
                now = after;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // the rest of the slot is spun, the rate stays right
        }
        while (clock.nanoTime() < target) {
            clock.spinWait();
        }
        sleepPeak = Math.max(MIN_PEAK, sleepPeak - DECAY);
    }

    /** The margin the limiter currently keeps before the end of the slot (for tests and debugging). */
    long currentMargin() {
        return sleepPeak + SAFETY;
    }
}
