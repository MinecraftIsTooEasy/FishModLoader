package net.xiaoyu233.fml.vulkan.testbot.cmd.nr05;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.render.frame.FramePacer;
import net.xiaoyu233.fml.vulkan.render.frame.FrameStats;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;
import net.xiaoyu233.fml.vulkan.window.FrameLimiter;
import net.xiaoyu233.fml.vulkan.window.InputManager;
import net.xiaoyu233.fml.vulkan.window.NativeWindow;
import net.xiaoyu233.fml.vulkan.window.TimerCore;

/** Devtest commands of work package NR-05: {@code latency}, {@code windowTest}, {@code capTest}, {@code timerTest}. */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-05";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new Latency(), new WindowTest(), new CapTest(), new TimerTest());
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * Synthetic mouse event timestamp versus present time. Args: {@code [samples=60]}. The latency mode is the one the game runs with
     * ({@code -Dfml.vulkan.latency=normal|low}; the frames in flight are fixed at start, so the two modes are two runs). With the new loop
     * (NR-07) the frame that polls after the event must also present it: event-to-present has to stay within one frame time plus the
     * present; with the phase-1 loop (no {@code pollInput}) the figure is the time to the end of the next present.
     */
    private static final class Latency implements DevCommand {
        @Override
        public String name() {
            return "latency";
        }

        @Override
        public String description() {
            return "input-to-present latency of synthetic mouse events for the active latency mode: latency [samples]";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            int samples = args.length > 0 ? Integer.parseInt(args[0]) : 60;
            String mode = VulkanConfig.latency();
            long[] lat = new long[samples];
            long[] period = new long[samples];
            int n = 0;
            boolean polled = false;
            for (int i = 0; i < samples; i++) {
                RenderThread.frames(1 + (i % 3)); // vary the phase of the event inside the frame
                long t = InputManager.injectMouseMove(1, 0);
                if (t == 0L) {
                    s.fail("latency", "the mouse does not exist (Display not created)");
                    return;
                }
                long base = FrameStats.frameCount();
                long[] found = waitPresented(t, base, 5_000);
                if (found == null) {
                    s.warn("latency sample " + i, "no frame presented within 5 s");
                    continue;
                }
                lat[n] = found[0];
                period[n] = found[1];
                polled |= found[2] == 1L;
                n++;
            }
            if (n == 0) {
                s.fail("latency", "no sample");
                return;
            }
            long[] sorted = java.util.Arrays.copyOf(lat, n);
            java.util.Arrays.sort(sorted);
            double median = sorted[n / 2] / 1e6;
            double p95 = sorted[Math.min(n - 1, (int) (n * 0.95))] / 1e6;
            double max = sorted[n - 1] / 1e6;
            long[] ps = java.util.Arrays.copyOf(period, n);
            java.util.Arrays.sort(ps);
            double framePeriod = ps[n / 2] / 1e6;
            s.info("latency[" + mode + "]", String.format(Locale.ROOT, "%d samples, event->present end median %.2f ms, p95 %.2f ms, max %.2f ms, frame time %.2f ms, %s",
                    n, median, p95, max, framePeriod, polled ? "poll in the presenting frame (pollInput active)" : "phase-1 loop (no pollInput): next present"));
            s.markdown(String.format(Locale.ROOT, "| latency mode | samples | median ms | p95 ms | max ms | frame ms |\n|---|---|---|---|---|---|\n| %s | %d | %.2f | %.2f | %.2f | %.2f |\n",
                    mode, n, median, p95, max, framePeriod));
            s.check(p95 <= 2.0 * framePeriod + 5.0, "present within the same loop iteration (p95 <= 2 frames + 5 ms)",
                    String.format(Locale.ROOT, "p95 %.2f ms vs frame %.2f ms", p95, framePeriod));
        }

        /** {latency nanos, frame nanos (cpu + present), 1 when the frame polled after the event}. */
        private static long[] waitPresented(long eventNanos, long baseFrame, long timeoutMs) throws Exception {
            CountDownLatch done = new CountDownLatch(1);
            long[] out = new long[3];
            Consumer<FrameStats.Frame> l = f -> {
                if (done.getCount() == 0 || f.index() < baseFrame) {
                    return;
                }
                long poll = FrameStats.pollNanos(f.index());
                long end = FrameStats.presentEndNanos(f.index());
                boolean polledAfter = poll >= eventNanos;
                if (polledAfter || poll == 0L && end > eventNanos && f.index() > baseFrame) {
                    out[0] = end - eventNanos;
                    out[1] = f.cpuNanos() + f.presentNanos();
                    out[2] = polledAfter ? 1L : 0L;
                    done.countDown();
                }
            };
            FrameStats.addListener(l);
            try {
                return done.await(timeoutMs, TimeUnit.MILLISECONDS) ? out : null;
            } finally {
                FrameStats.removeListener(l);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Fullscreen toggle and resize N times through the deferred frame-boundary path. Args: {@code [rounds=20]}. */
    private static final class WindowTest implements DevCommand {
        @Override
        public String name() {
            return "windowTest";
        }

        @Override
        public String description() {
            return "fullscreen toggle + window resize 20x (deferred switch, swapchain recreation): windowTest [rounds]";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 20;
            if (!NativeWindow.isCreated()) {
                s.fail("windowTest", "no window");
                return;
            }
            boolean startFs = RenderThread.call(NativeWindow::isFullscreen, 10_000);
            int[] startSize = RenderThread.call(() -> new int[] {NativeWindow.width(), NativeWindow.height()}, 10_000);
            int failures = 0;
            for (int i = 0; i < rounds; i++) {
                boolean target = (i % 2 == 0) != startFs;
                try {
                    RenderThread.run(() -> NativeWindow.requestFullscreen(target), 10_000);
                    RenderThread.frames(4);
                    boolean state = RenderThread.call(NativeWindow::isFullscreen, 10_000);
                    int[] size = RenderThread.call(() -> new int[] {NativeWindow.width(), NativeWindow.height()}, 10_000);
                    if (state != target || size[0] <= 0 || size[1] <= 0) {
                        failures++;
                        s.fail("fullscreen toggle " + i, "state " + state + " size " + size[0] + "x" + size[1]);
                    }
                } catch (Exception e) {
                    failures++;
                    s.fail("fullscreen toggle " + i, e.toString());
                }
            }
            RenderThread.run(() -> NativeWindow.requestFullscreen(startFs), 10_000);
            RenderThread.frames(4);
            s.check(failures == 0, rounds + " fullscreen toggles", failures + " failed");
            if (!RenderThread.call(NativeWindow::isFullscreen, 10_000)) {
                int rf = 0;
                for (int i = 0; i < rounds; i++) {
                    int w = startSize[0] + (i % 2 == 0 ? 120 : -60);
                    int h = startSize[1] + (i % 2 == 0 ? 80 : -40);
                    try {
                        RenderThread.run(() -> {
                            try {
                                NativeWindow.setDisplayMode(new org.lwjgl.opengl.DisplayMode(w, h));
                            } catch (org.lwjgl.LWJGLException e) {
                                throw new IllegalStateException(e);
                            }
                        }, 10_000);
                        RenderThread.frames(3);
                        RenderThread.frames(2); // the switch is deferred to the frame boundary
                        int[] size = RenderThread.call(() -> new int[] {NativeWindow.width(), NativeWindow.height()}, 10_000);
                        boolean resized = size[0] == w && size[1] == h || size[0] != startSize[0] || size[1] != startSize[1];
                        if (!resized || size[0] <= 0 || size[1] <= 0) {
                            rf++;
                            s.fail("resize " + i, "resized=" + resized + " size " + size[0] + "x" + size[1]);
                        }
                    } catch (Exception e) {
                        rf++;
                        s.fail("resize " + i, e.toString());
                    }
                }
                RenderThread.run(() -> {
                    try {
                        NativeWindow.setDisplayMode(new org.lwjgl.opengl.DisplayMode(startSize[0], startSize[1]));
                    } catch (org.lwjgl.LWJGLException e) {
                        throw new IllegalStateException(e);
                    }
                }, 10_000);
                s.check(rf == 0, rounds + " resizes", rf + " failed");
            } else {
                s.warn("resize", "skipped: the window stayed fullscreen");
            }
            s.info("validation", "validation messages are counted by the test bot's log watch for this section (0 expected)");
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Context caps: the limiter is run for real at the capped rate (no window needed). Args: none. */
    private static final class CapTest implements DevCommand {
        @Override
        public String name() {
            return "capTest";
        }

        @Override
        public String description() {
            return "unfocused cap (30 FPS) and minimised cap (10 FPS) measured with the real frame limiter";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            int unfocused = VulkanConfig.capUnfocused();
            int minimised = VulkanConfig.capMinimised();
            s.check(FramePacer.effectiveFps(120, false, false, unfocused, minimised) == unfocused, "menu 120 unfocused -> cap", "wrong");
            s.check(FramePacer.effectiveFps(20, false, false, unfocused, minimised) == 20, "menu 20 unfocused -> never raised", "wrong");
            s.check(FramePacer.effectiveFps(0, false, false, unfocused, minimised) == unfocused, "unlimited unfocused -> cap", "wrong");
            s.check(FramePacer.effectiveFps(120, true, false, unfocused, minimised) == 120, "focused -> menu value", "wrong");
            s.check(FramePacer.effectiveFps(120, false, true, unfocused, minimised) == minimised, "minimised -> minimised cap", "wrong");
            measure(s, "unfocused", FramePacer.effectiveFps(120, false, false, unfocused, minimised), 60);
            measure(s, "minimised", FramePacer.effectiveFps(120, false, true, unfocused, minimised), 20);
        }

        private static void measure(Report.Section s, String what, int fps, int frames) {
            FrameLimiter limiter = FrameLimiter.create();
            limiter.limit(fps);
            long t0 = System.nanoTime();
            for (int i = 0; i < frames; i++) {
                limiter.limit(fps);
            }
            double seconds = (System.nanoTime() - t0) / 1e9;
            double measured = frames / seconds;
            s.info(what + " cap", String.format(Locale.ROOT, "target %d FPS, measured %.2f FPS over %d frames", fps, measured, frames));
            s.check(Math.abs(measured - fps) <= Math.max(0.5, fps * 0.03), what + " cap " + fps + " FPS", String.format(Locale.ROOT, "measured %.2f", measured));
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * Tick count over 60 s of the nanosecond timer versus MITE's timer algorithm, both fed by the same simulated clock (frame times in whole
     * milliseconds, where MITE's millisecond clock is exact, so the counts must be identical; microsecond jitter is reported). Args: none.
     */
    private static final class TimerTest implements DevCommand {
        @Override
        public String name() {
            return "timerTest";
        }

        @Override
        public String description() {
            return "tick count over 60 s: nanosecond timer vs MITE's timer (+-0) at several frame rates";
        }

        @Override
        public void run(Report.Section s, String[] args) {
            int[] frameMs = {1, 2, 4, 7, 8, 16, 17, 33, 50, 80, 120, 400};
            for (int ms : frameMs) {
                long mite = simulate(ms * 1_000_000L, 0L, true);
                long ours = simulate(ms * 1_000_000L, 0L, false);
                s.check(mite == ours, "60 s at " + ms + " ms frames: ticks " + mite, "MITE " + mite + " vs native " + ours);
            }
            long[][] stalls = {{0L, 0L}, {2_000_000_000L, 0L}, {0L, 1_500_000_000L}, {0L, 5_000_000_000L}, {500_000_000L, -300_000_000L},
                    {1_200_000_000L, 1_001_000_000L}};
            for (long[] st : stalls) {
                for (int ms : new int[] {7, 16, 50}) {
                    long mite = simulate(ms * 1_000_000L, 0L, true, st[0], st[1], 0L);
                    long ours = simulate(ms * 1_000_000L, 0L, false, st[0], st[1], 0L);
                    s.check(mite == ours, "start gap " + st[0] / 1_000_000L + " ms, stall " + st[1] / 1_000_000L + " ms, " + ms + " ms frames: ticks "
                            + mite, "MITE model " + mite + " vs native " + ours);
                }
            }
            List<String> jitter = new ArrayList<>();
            for (int ms : new int[] {7, 16, 33}) {
                long mite = simulate(ms * 1_000_000L, 400_000L, true);
                long ours = simulate(ms * 1_000_000L, 400_000L, false);
                jitter.add(ms + " ms +-0.4 ms: MITE " + mite + ", native " + ours);
                s.check(Math.abs(mite - ours) <= 1, "jittered frames within one tick (" + ms + " ms)", mite + " vs " + ours);
            }
            s.info("jitter", String.join("; ", jitter) + " (MITE quantises each reading to a millisecond, the nanosecond clock does not)");
        }

        /** Total ticks over 60 simulated seconds; deterministic pseudo-random jitter of +-{@code jitterNanos}. */
        private static long simulate(long frameNanos, long jitterNanos, boolean mite) {
            return simulate(frameNanos, jitterNanos, mite, 0L, 0L, 0L);
        }

        /**
         * As above, with a gap between construction and the first update ({@code startGapNanos}) and, every 200th frame, a stall of
         * {@code stallNanos} (negative values step the clock back). The MITE model is the decompiled Timer (lastHRTime starts at 0).
         */
        private static long simulate(long frameNanos, long jitterNanos, boolean mite, long startGapNanos, long stallNanos, long unused) {
            long seed = 12345L;
            long created = 5_000_000_000L;
            long now = created + startGapNanos;
            long end = now + 60_000_000_000L;
            long frame = 0;
            float partial = 0f;
            long ticks = 0;
            TimerCore core = new TimerCore(created);
            // MITE state
            double lastHr = 0.0;
            long lastSyncHr = created / 1_000_000L;
            long lastSys = created / 1_000_000L;
            long acc = 0;
            double adjust = 1.0;
                        while (now < end) {
                float total;
                if (mite) {
                    long sys = now / 1_000_000L;
                    long dt = sys - lastSys;
                    long hr = now / 1_000_000L;
                    double hrSec = hr / 1000.0;
                    if (dt <= 1000L && dt >= 0L) {
                        acc += dt;
                        if (acc > 1000L) {
                            long d = hr - lastSyncHr;
                            double ratio = (double) acc / d;
                            adjust = adjust + (ratio - adjust) * 0.2F;
                            lastSyncHr = hr;
                            acc = 0L;
                        }
                    } else {
                        lastHr = hrSec;
                    }
                    lastSys = sys;
                    double delta = (hrSec - lastHr) * adjust;
                    lastHr = hrSec;
                    delta = Math.max(0.0, Math.min(1.0, delta));
                    total = TimerCore.accumulate(partial, delta, 1.0f, 20.0f);
                } else {
                    total = TimerCore.accumulate(partial, core.deltaSeconds(now), 1.0f, 20.0f);
                }
                int t = (int) total;
                partial = total - t;
                ticks += Math.min(t, 10);
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                long j = jitterNanos == 0L ? 0L : (long) (((seed >>> 33) % (2 * jitterNanos + 1)) - jitterNanos);
                now += frameNanos + j;
                if (stallNanos != 0L && ++frame % 200 == 0) {
                    now += stallNanos;
                }
            }
            return ticks;
        }
    }
}
