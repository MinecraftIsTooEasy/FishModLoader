package net.xiaoyu233.fml.vulkan.window;

/**
 * The clock behind the {@code Timer} mixin (PAC-06): seconds elapsed between two updates from {@code System.nanoTime()}, clamped to
 * [0, 1] like MITE does. MITE's timer derives the time from millisecond readings and corrects it with a drift term between the wall
 * clock and the high resolution clock; the nanosecond clock needs no correction and the tick arithmetic stays that of MITE (the
 * float partial-tick accumulation is done by the mixin with the game's own fields), so the tick counts are identical.
 */
public final class TimerCore {
    private long lastNanos;
    private boolean started;
    private final long createdNanos;

    public TimerCore() {
        this(System.nanoTime());
    }

    public TimerCore(long createdNanos) {
        this.createdNanos = createdNanos;
    }

    /**
     * Seconds since the previous call, MITE's rule: a gap of more than one second (or a negative one) between two updates is a stall
     * and yields 0 (the clock is resynchronised, no catch-up ticks). The first call compares against the construction time: within
     * one second MITE's "last time" is still zero and the result is the maximum (1.0), after more than a second it is 0.
     */
    public double deltaSeconds(long nowNanos) {
        if (!started) {
            started = true;
            double sinceCreate = (nowNanos - createdNanos) / 1.0e9;
            lastNanos = nowNanos;
            return sinceCreate > 1.0 || sinceCreate < 0.0 ? 0.0 : 1.0;
        }
        double d = (nowNanos - lastNanos) / 1.0e9;
        lastNanos = nowNanos;
        if (d > 1.0 || d < 0.0) {
            return 0.0;
        }
        return d;
    }

    /** Partial ticks after adding {@code deltaSeconds} (MITE's float arithmetic; the caller splits the result into ticks and rest). */
    public static float accumulate(float partial, double deltaSeconds, float timerSpeed, float ticksPerSecond) {
        return (float) (partial + deltaSeconds * timerSpeed * ticksPerSecond);
    }

    /** Ticks the game runs for one frame: the integer part of the partial ticks, at most 10. */
    public static int ticksOf(float partialTotal) {
        return Math.min((int) partialTotal, 10);
    }
}
