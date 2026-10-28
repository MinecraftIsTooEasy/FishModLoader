package net.xiaoyu233.fml.vulkan.render.gpu;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.core.OffscreenTarget;
import net.xiaoyu233.fml.vulkan.core.RenderCore;

import java.util.ArrayList;

/**
 * Lifecycle and frame hooks of the GPU layer (1.1, 5.6): the first step of the native start order. It drives what has to happen at
 * frame boundaries for the arenas (retire deferred frees, release idle pages), the timestamp queries ({@link GpuTimer}), the pending
 * readbacks ({@link Readback}) and the framebuffer-following {@link RenderTarget}s (re-created when the MAIN target is resized), and
 * releases all of it at shutdown. The hooks are installed lazily the first time an arena, target, readback or timer is used, so the
 * layer also works with the native renderer switched off (test commands). Render thread only.
 */
public final class GpuCore {
    private static final ArrayList<GpuArena> ARENAS = new ArrayList<>();
    private static final ArrayList<RenderTarget> TARGETS = new ArrayList<>();
    private static Hooks hooks;

    private GpuCore() {
    }

    /** Installs the frame hooks and logs the device features; idempotent. Called by {@code VulkanBackend.start} after the phase-1 modules. */
    public static void init() {
        ensureHooks();
        VkLog.LOGGER.info("GpuCore: {}, {} frame(s) in flight, {} swapchain image(s){}", DeviceFeatures.caps(),
                RenderCore.framesInFlight(), RenderCore.swapchainImageCount(), RenderCore.isLowLatency() ? ", low latency" : "");
    }

    /** Releases arenas, targets, readbacks and timers; called by the backend after the device went idle. */
    public static void shutdown() {
        Readback.shutdown();
        GpuTimer.shutdown();
        for (RenderTarget t : new ArrayList<>(TARGETS)) {
            t.destroy();
        }
        TARGETS.clear();
        for (GpuArena a : new ArrayList<>(ARENAS)) {
            a.destroy();
        }
        ARENAS.clear();
        hooks = null;
    }

    /** Frames in flight in use (1 with {@code fml.vulkan.latency=low}). */
    public static int framesInFlight() {
        return RenderCore.framesInFlight();
    }

    /** Number of the frame being recorded. */
    public static long frame() {
        return RenderCore.frameNumber();
    }

    /**
     * {@code fml.vulkan.latency=low} with VK_KHR_present_wait: blocks until the previous frame was shown (at most {@code timeoutNanos}) so
     * input is sampled right before the GPU starts the next frame. Returns false when the extension is not enabled, nothing was
     * presented yet, or on timeout; callers then fall back to their own pacing.
     */
    public static boolean waitForPreviousPresent(long timeoutNanos) {
        return RenderCore.waitForPresent(timeoutNanos);
    }

    // ---------------------------------------------------------------------------------------------------------

    static void register(GpuArena a) {
        ensureHooks();
        ARENAS.add(a);
    }

    static void unregister(GpuArena a) {
        ARENAS.remove(a);
    }

    static void register(RenderTarget t) {
        ensureHooks();
        TARGETS.add(t);
    }

    static void unregister(RenderTarget t) {
        TARGETS.remove(t);
    }

    /** Makes sure the frame listener is installed in the current {@code RenderCore}; no-op before {@code RenderCore.init}. */
    static void ensureHooks() {
        if (!RenderCore.isInitialized()) {
            return;
        }
        if (hooks == null) {
            hooks = new Hooks();
            RenderCore.addListener(hooks);
        }
    }

    private static final class Hooks implements RenderCore.FrameListener {
        @Override
        public void onFrameBegin() {
            long frame = RenderCore.frameNumber();
            int inFlight = RenderCore.framesInFlight();
            for (int i = 0; i < ARENAS.size(); i++) {
                ARENAS.get(i).onFrameBegin(frame, inFlight);
            }
            GpuTimer.onFrameBegin();
            Readback.onFrameBegin(frame, inFlight);
        }

        @Override
        public void onCommandBufferReset() {
            GpuTimer.onCommandBufferReset();
        }

        @Override
        public void beforePresent() {
            GpuTimer.endFrame();
        }

        @Override
        public void onResize(int w, int h) {
            OffscreenTarget main = RenderCore.mainTarget();
            for (int i = 0; i < TARGETS.size(); i++) {
                TARGETS.get(i).onMainResized(main.width(), main.height());
            }
        }
    }
}
