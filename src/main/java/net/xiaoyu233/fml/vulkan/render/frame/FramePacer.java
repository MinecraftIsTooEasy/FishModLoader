package net.xiaoyu233.fml.vulkan.render.frame;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.Minecraft;
import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.render.gpu.DeviceFeatures;
import net.xiaoyu233.fml.vulkan.render.gpu.GpuTimer;
import net.xiaoyu233.fml.vulkan.render.state.Frame;
import net.xiaoyu233.fml.vulkan.window.FrameLimiter;
import net.xiaoyu233.fml.vulkan.window.NativeWindow;

/**
 * Frame loop services (4.1 to 4.4, 5.10), render thread only. The game loop calls, in this order: {@link #pollInput()} right before it
 * renders (the latest input events are in the queues, INP-01), {@link #endFrame()} when the frame is recorded (present now, PAC-01),
 * {@link #runDeferredAfterPresent()} (fullscreen / resize / vsync switches of the frame boundary, WIN-01) and {@link #limit(int)}
 * (sleep after the present, PAC-02; capped while unfocused or minimised, PAC-03).
 *
 * <p>Nothing here allocates per frame: the stats ring is primitive arrays, the deferred queue is only touched when something was
 * deferred.
 */
public final class FramePacer {
    private static final Queue<Runnable> DEFERRED = new ConcurrentLinkedQueue<>();
    /** Work that only runs when the frame limiter has spare time ({@code fml.vulkan.downtimeAfterPresent}). */
    private static final Queue<Runnable> DOWNTIME = new ConcurrentLinkedQueue<>();

    private static boolean configRead;
    private static boolean lowLatency;
    private static boolean guiPerFrame;
    private static boolean downtime;
    private static boolean gpuTimers;
    private static int capUnfocused;
    private static int capMinimised;

    private static boolean loopRunning;
    private static long frameStart;
    private static boolean limitSeen;
    private static volatile long lastPresentEnd;
    private static volatile Runnable pollProbe;

    private static boolean waitResolved;
    private static long presentWaitNanos = 100_000_000L;
    private static boolean waitTimeoutLogged;
    public static final String PROP_PRESENT_WAIT_MS = "fml.vulkan.presentWaitMs";

    private FramePacer() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // configuration and latency policy (4.2)
    // ---------------------------------------------------------------------------------------------------------

    private static void readConfig() {
        lowLatency = "low".equals(VulkanConfig.latency());
        guiPerFrame = VulkanConfig.inputGuiPerFrame();
        downtime = VulkanConfig.downtimeAfterPresent();
        gpuTimers = VulkanConfig.gpuTimers();
        capUnfocused = VulkanConfig.capUnfocused();
        capMinimised = VulkanConfig.capMinimised();
        configRead = true;
    }

    /**
     * Latency policy, called before the backend starts: {@code normal} changes nothing (frames in flight and swapchain image count of
     * phase 1); {@code low} asks for one frame in flight and a swapchain of {@code minImageCount} images (the latter through the
     * {@value #PROP_MIN_SWAPCHAIN} property that the core reads). An explicit {@code fml.vulkan.framesInFlight} is respected.
     */
    public static void applyLatencyPolicy() {
        readConfig();
        if (!lowLatency) {
            return;
        }
        if (System.getProperty(VulkanConfig.PROP_FRAMES_IN_FLIGHT) == null) {
            System.setProperty(VulkanConfig.PROP_FRAMES_IN_FLIGHT, "1");
        }
        if (System.getProperty(PROP_MIN_SWAPCHAIN) == null) {
            System.setProperty(PROP_MIN_SWAPCHAIN, "true");
        }
        VkLog.LOGGER.info("Latency policy 'low': {} frame(s) in flight, minimal swapchain, present_wait {}", VulkanConfig.framesInFlight(),
                DeviceFeatures.presentWait() ? "available" : "not available (queue depth only)");
    }

    /** Property read by the core: swapchain with {@code minImageCount} images instead of {@code minImageCount + 1}. */
    public static final String PROP_MIN_SWAPCHAIN = "fml.vulkan.swapchainMinImages";

    /** True with {@code fml.vulkan.latency=low}. */
    public static boolean isLowLatency() {
        if (!configRead) {
            readConfig();
        }
        return lowLatency;
    }

    // ---------------------------------------------------------------------------------------------------------
    // frame loop
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Right before rendering: with {@code latency=low} and {@code present_wait} waits for the previous present (the GPU never queues
     * more than one frame ahead), then polls the window events (INP-01) and, with {@code fml.vulkan.input.guiPerFrame}, lets an open
     * screen handle its input once per frame (INP-10).
     */
    public static void pollInput() {
        if (!configRead) {
            readConfig();
        }
        loopRunning = true;
        long now = System.nanoTime();
        if (frameStart == 0L) {
            frameStart = now;
        }
        if (lowLatency && DeviceFeatures.presentWait()) {
            waitForPreviousPresent();
        }
        FrameStats.notePoll(System.nanoTime());
        Runnable probe = pollProbe;
        if (probe != null) {
            pollProbe = null;
            probe.run();
        }
        NativeWindow.pollEvents();
        if (guiPerFrame) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.isGuiOpen()) {
                try {
                    mc.sendInputToGui();
                } catch (Throwable t) {
                    net.minecraft.CrashReport report = net.minecraft.CrashReport.makeCrashReport(t, "Updating screen events");
                    net.minecraft.CrashReportCategory category = report.makeCategory("Affected screen");
                    category.addCrashSectionCallable("Screen name", () -> {
                        net.minecraft.GuiScreen screen = mc.currentScreen;
                        return screen == null ? "none" : screen.getClass().getCanonicalName();
                    });
                    throw new net.minecraft.ReportedException(report);
                }
            }
        }
    }

    /**
     * Present now (PAC-01): flushes the batches still pending, records the GPU timestamps of the frame, presents and stores the frame in
     * {@link FrameStats}.
     */
    public static void endFrame() {
        if (!NativeWindow.isCreated()) {
            return;
        }
        long t0 = System.nanoTime();
        if (frameStart == 0L) {
            frameStart = t0;
        }
        Frame.flush();
        if (gpuTimers) {
            GpuTimer.endFrame();
        }
        NativeWindow.present();
        long t1 = System.nanoTime();
        lastPresentEnd = t1;
        FrameStats.record(t0 - frameStart, t1 - t0, gpuTimers ? GpuTimer.gpuFrameMs() : 0.0, t1);
        if (!limitSeen) {
            frameStart = t1; // a loop without limit(): the next frame starts at the present
        }
    }

    /**
     * Sleeps until the end of the frame slot (after the present, PAC-02). The menu value {@code fps} stays owned by the game; while the
     * window is unfocused or minimised the context caps apply and never raise it (PAC-03); {@code fps <= 0} means unlimited.
     */
    public static void limit(int fps) {
        limitSeen = true;
        if (!configRead) {
            readConfig();
        }
        if (downtime) {
            runDowntime();
        }
        fps = net.xiaoyu233.fml.vulkan.render.frame.FpsPolicy.clampFps(fps);
        int cap = effectiveFps(fps, NativeWindow.isActive(), NativeWindow.isIconified(), capUnfocused, capMinimised);
        FrameLimiter.sync(cap);
        frameStart = System.nanoTime();
    }

    /**
     * The frame rate the limiter gets: {@code fps} (<= 0: unlimited) lowered to {@code capMinimised} while iconified, to
     * {@code capUnfocused} while the window has no focus, never raised.
     */
    public static int effectiveFps(int fps, boolean active, boolean iconified, int capUnfocused, int capMinimised) {
        int cap = iconified ? capMinimised : active ? 0 : capUnfocused;
        if (cap <= 0) {
            return fps;
        }
        return fps <= 0 ? cap : Math.min(fps, cap);
    }

    /** Queues {@code afterPresent} for {@link #runDeferredAfterPresent()}; any thread. */
    public static void defer(Runnable afterPresent) {
        DEFERRED.add(afterPresent);
    }

    /**
     * Queues work for the spare time of the frame limiter (it runs at the start of the next {@link #limit(int)} when
     * {@code fml.vulkan.downtimeAfterPresent=true}, otherwise with the deferred actions). Any thread.
     */
    public static void deferDowntime(Runnable work) {
        if (!configRead) {
            readConfig();
        }
        (downtime ? DOWNTIME : DEFERRED).add(work);
    }

    /** Runs and clears the deferred actions in order (render thread, right after the present). */
    public static void runDeferredAfterPresent() {
        Runnable r;
        while ((r = DEFERRED.poll()) != null) {
            runGuarded(r);
        }
    }

    private static void runDowntime() {
        Runnable r;
        while ((r = DOWNTIME.poll()) != null) {
            runGuarded(r);
        }
    }

    private static void runGuarded(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            VkLog.LOGGER.error("Deferred frame action failed", e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // state used by the window and the devtest commands
    // ---------------------------------------------------------------------------------------------------------

    /** True once the game loop calls {@link #pollInput()}: window switches are then deferred to the frame boundary. */
    public static boolean loopRunning() {
        return loopRunning;
    }

    /** {@code System.nanoTime()} at the end of the last present ({@code 0} before the first). */
    public static long lastPresentEndNanos() {
        return lastPresentEnd;
    }

    /** Devtest: runs {@code probe} once at the start of the next {@link #pollInput()}, on the render thread, before the events are polled. */
    public static void probeNextPoll(Runnable probe) {
        pollProbe = probe;
    }

    /** Window destroyed: the loop is not running any more, queued actions are dropped. */
    public static void reset() {
        loopRunning = false;
        frameStart = 0L;
        limitSeen = false;
        pollProbe = null;
        DEFERRED.clear();
        DOWNTIME.clear();
    }

    /** Waits (bounded) for the previous present when latency=low and present_wait is enabled; no-op otherwise (e.g. macOS). */
    private static void waitForPreviousPresent() {
        if (!lowLatency || !DeviceFeatures.presentWait()) {
            return;
        }
        if (!waitResolved) {
            waitResolved = true;
            long ms = 100L;
            try {
                String v = System.getProperty(PROP_PRESENT_WAIT_MS);
                if (v != null) {
                    ms = Math.max(1L, Long.parseLong(v.trim()));
                }
            } catch (NumberFormatException e) {
                VkLog.LOGGER.warn("Invalid {}, using 100 ms", PROP_PRESENT_WAIT_MS);
            }
            presentWaitNanos = ms * 1_000_000L;
            VkLog.LOGGER.info("Latency policy 'low': present_wait used (timeout {} ms)", ms);
        }
        if (!RenderCore.waitForPresent(presentWaitNanos) && !waitTimeoutLogged && RenderCore.swapchainImageCount() > 0) {
            // false also covers "nothing presented yet"; only log once, at debug-relevant info level
            waitTimeoutLogged = true;
            VkLog.LOGGER.info("present_wait returned without a completed wait (first present, timeout {} ms or error); never blocks longer",
                    presentWaitNanos / 1_000_000L);
        }
    }
}
