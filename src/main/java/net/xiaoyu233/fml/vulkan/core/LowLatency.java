package net.xiaoyu233.fml.vulkan.core;

import java.nio.LongBuffer;
import java.util.Locale;
import net.xiaoyu233.fml.vulkan.VkLog;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.AMDAntiLag;
import org.lwjgl.vulkan.NVLowLatency2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAntiLagDataAMD;
import org.lwjgl.vulkan.VkAntiLagPresentationInfoAMD;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGetLatencyMarkerInfoNV;
import org.lwjgl.vulkan.VkLatencySleepInfoNV;
import org.lwjgl.vulkan.VkLatencySleepModeInfoNV;
import org.lwjgl.vulkan.VkLatencyTimingsFrameReportNV;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSetLatencyMarkerInfoNV;

import static org.lwjgl.vulkan.VK10.*;

/**
 * NVIDIA Reflex (VK_NV_low_latency2) and AMD Anti-Lag (VK_AMD_anti_lag), config key {@code fml.vulkan.reflex=off|on|boost}.
 * Render thread only. No Streamline SDK: the driver extensions are called directly through LWJGL's bindings.
 *
 * <p>Frame mapping (ARCHITECTURE-NATIVE "Reflex"): {@link #sleep} replaces the software frame limiter at the end of
 * {@code FramePacer.limit} (the frame cap goes to {@code minimumIntervalUs} / {@code maxFPS}); {@link #beginFrame} at the head of
 * {@code runGameLoop} = SIMULATION_START and a new present id; INPUT_SAMPLE right before {@code glfwPollEvents}; SIMULATION_END +
 * RENDERSUBMIT_START when {@code FramePacer.pollInput} returns (the camera and the command recording follow); RENDERSUBMIT_END after
 * the frame's {@code vkQueueSubmit}; PRESENT_START / PRESENT_END around {@code vkQueuePresentKHR}; TRIGGER_FLASH on a left click.
 *
 * <p>Without either extension (MoltenVK, Intel, older drivers, {@code reflex=off}) every entry point returns after one static
 * boolean test: no driver call, no allocation. Every per-frame struct is allocated once in {@link #init}.
 */
public final class LowLatency {
    public static final int MODE_OFF = 0;
    public static final int MODE_ON = 1;
    public static final int MODE_BOOST = 2;
    private static final long SLEEP_WAIT_TIMEOUT_NANOS = 200_000_000L;
    private static final int MAX_TIMINGS = 64;

    /** Extension enabled on the device (NVIDIA / AMD); both false = the zero-overhead path. */
    private static boolean nv;
    private static boolean amd;
    /** Why neither is available, for F3 and the log (set by DeviceSetup). */
    private static String unsupportedReason = "no Vulkan device";
    private static int mode = MODE_ON;

    private static VkDevice device;
    private static long swapchain;
    private static boolean sleepModeDirty = true;
    private static int appliedIntervalUs = -1;
    private static int appliedMode = -1;
    private static boolean lastSleepActive;
    private static boolean warnedTimeout;
    private static boolean amdOffSent;

    // present ids: one per frame (SIMULATION_START), strictly increasing over the device lifetime (VUID-VkPresentIdKHR-presentIds-04999)
    private static long idCounter;
    private static long frameId;
    private static boolean framePresented = true;
    private static long amdFrame;
    private static int amdMaxFps;

    // preallocated structs (native heap, freed in destroy)
    private static long timeline;
    private static long timelineValue;
    private static VkLatencySleepModeInfoNV sleepModeInfo;
    private static VkLatencySleepInfoNV sleepInfo;
    private static VkSemaphoreWaitInfo waitInfo;
    private static LongBuffer waitSemaphores;
    private static LongBuffer waitValues;
    private static VkSetLatencyMarkerInfoNV markerInfo;
    private static VkGetLatencyMarkerInfoNV timingsInfo;
    private static VkLatencyTimingsFrameReportNV.Buffer timings;
    private static VkAntiLagDataAMD antiLagData;
    private static VkAntiLagPresentationInfoAMD antiLagPresent;

    private LowLatency() {
    }

    /** Parses {@code off | on | boost}; anything else = on. */
    static int parseMode(String v) {
        return switch (v == null ? "" : v.trim().toLowerCase(Locale.ROOT)) {
            case "off" -> MODE_OFF;
            case "boost" -> MODE_BOOST;
            default -> MODE_ON;
        };
    }

    /** DeviceSetup result: which extension is enabled (at most one is used, NVIDIA first) and why not when neither. */
    static void configure(boolean nvEnabled, boolean amdEnabled, String reason, int configuredMode) {
        nv = nvEnabled;
        amd = amdEnabled && !nvEnabled;
        unsupportedReason = reason;
        mode = configuredMode;
    }

    /** After the logical device exists: timeline semaphore (NVIDIA) and the per-frame structs. */
    static void init(VkDevice dev) {
        device = dev;
        idCounter = 0L;
        frameId = 0L;
        framePresented = true;
        swapchain = 0L;
        sleepModeDirty = true;
        appliedIntervalUs = -1;
        appliedMode = -1;
        lastSleepActive = false;
        amdOffSent = false;
        if (nv) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                        .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0L);
                VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                        .pNext(type.address());
                LongBuffer p = stack.mallocLong(1);
                int r = vkCreateSemaphore(dev, info, null, p);
                if (r != VK_SUCCESS) {
                    disable("timeline semaphore: " + VkUtil.resultName(r));
                    return;
                }
                timeline = p.get(0);
                timelineValue = 0L;
            }
            sleepModeInfo = VkLatencySleepModeInfoNV.calloc().sType$Default();
            sleepInfo = VkLatencySleepInfoNV.calloc().sType$Default().signalSemaphore(timeline);
            waitSemaphores = MemoryUtil.memAllocLong(1).put(0, timeline);
            waitValues = MemoryUtil.memAllocLong(1);
            waitInfo = VkSemaphoreWaitInfo.calloc().sType$Default().pSemaphores(waitSemaphores).pValues(waitValues).semaphoreCount(1);
            markerInfo = VkSetLatencyMarkerInfoNV.calloc().sType$Default();
            timings = VkLatencyTimingsFrameReportNV.calloc(MAX_TIMINGS);
            for (int i = 0; i < MAX_TIMINGS; i++) {
                VkLatencyTimingsFrameReportNV.nsType(timings.address() + (long) i * VkLatencyTimingsFrameReportNV.SIZEOF,
                        NVLowLatency2.VK_STRUCTURE_TYPE_LATENCY_TIMINGS_FRAME_REPORT_NV);
            }
            timingsInfo = VkGetLatencyMarkerInfoNV.calloc().sType$Default();
        } else if (amd) {
            antiLagPresent = VkAntiLagPresentationInfoAMD.calloc().sType$Default();
            antiLagData = VkAntiLagDataAMD.calloc().sType$Default();
        }
        VkLog.LOGGER.info("Low latency: {}", statusText());
    }

    /** Before the device is destroyed (idle). Null safe, idempotent. */
    static void destroy(VkDevice dev) {
        if (timeline != 0L && dev != null) {
            vkDestroySemaphore(dev, timeline, null);
        }
        timeline = 0L;
        if (sleepModeInfo != null) sleepModeInfo.free();
        if (sleepInfo != null) sleepInfo.free();
        if (waitInfo != null) waitInfo.free();
        if (waitSemaphores != null) MemoryUtil.memFree(waitSemaphores);
        if (waitValues != null) MemoryUtil.memFree(waitValues);
        if (markerInfo != null) markerInfo.free();
        if (timingsInfo != null) timingsInfo.free();
        if (timings != null) timings.free();
        if (antiLagData != null) antiLagData.free();
        if (antiLagPresent != null) antiLagPresent.free();
        sleepModeInfo = null;
        sleepInfo = null;
        waitInfo = null;
        waitSemaphores = null;
        waitValues = null;
        markerInfo = null;
        timingsInfo = null;
        timings = null;
        antiLagData = null;
        antiLagPresent = null;
        nv = false;
        amd = false;
        device = null;
        swapchain = 0L;
        lastSleepActive = false;
        unsupportedReason = "no Vulkan device";
    }

    /** Runtime failure (device lost, driver error): back to the software limiter for good, logged once. */
    private static void disable(String why) {
        if (nv || amd) {
            VkLog.LOGGER.warn("Low latency ({}) turned off: {}", nv ? "NVIDIA Reflex" : "AMD Anti-Lag", why);
        }
        nv = false;
        amd = false;
        lastSleepActive = false;
        unsupportedReason = "failed: " + why;
    }

    // ---------------------------------------------------------------------------------------------------------
    // swapchain (RenderCore / Swapchain)
    // ---------------------------------------------------------------------------------------------------------

    /** True when swapchains must be created with VkSwapchainLatencyCreateInfoNV and submits/presents carry present ids for NVIDIA. */
    static boolean nvEnabled() {
        return nv;
    }

    /** The swapchain in use changed (created, recreated, destroyed = 0): the sleep mode is per swapchain and is applied again. */
    static void onSwapchain(long handle) {
        swapchain = handle;
        sleepModeDirty = true;
    }

    /** Present id of the frame being built; a frame presented twice (loading screens) gets a fresh one. */
    static long currentPresentId() {
        if (frameId == 0L || framePresented) {
            frameId = ++idCounter;
            framePresented = false;
        }
        return frameId;
    }

    // ---------------------------------------------------------------------------------------------------------
    // frame loop (FramePacer, RenderCore, InputManager)
    // ---------------------------------------------------------------------------------------------------------

    /** Head of the game loop iteration: new frame id and SIMULATION_START. */
    public static void beginFrame() {
        if (!nv) {
            return;
        }
        frameId = ++idCounter;
        framePresented = false;
        marker(NVLowLatency2.VK_LATENCY_MARKER_SIMULATION_START_NV);
    }

    /** Right before glfwPollEvents. */
    public static void inputSample() {
        if (nv) {
            marker(NVLowLatency2.VK_LATENCY_MARKER_INPUT_SAMPLE_NV);
        }
    }

    /** Input handled, the camera update and the command recording start. */
    public static void simulationEndRenderStart() {
        if (nv) {
            marker(NVLowLatency2.VK_LATENCY_MARKER_SIMULATION_END_NV);
            marker(NVLowLatency2.VK_LATENCY_MARKER_RENDERSUBMIT_START_NV);
        }
    }

    /** After the frame's vkQueueSubmit. */
    static void renderSubmitEnd() {
        if (nv) {
            marker(NVLowLatency2.VK_LATENCY_MARKER_RENDERSUBMIT_END_NV);
        }
    }

    /** Right before vkQueuePresentKHR (NVIDIA marker, AMD present stage). */
    static void presentStart() {
        if (nv) {
            marker(NVLowLatency2.VK_LATENCY_MARKER_PRESENT_START_NV);
        } else if (amd && mode != MODE_OFF && lastSleepActive) {
            antiLagData.mode(AMDAntiLag.VK_ANTI_LAG_MODE_ON_AMD).maxFPS(amdMaxFps)
                    .pPresentationInfo(antiLagPresent.stage(AMDAntiLag.VK_ANTI_LAG_STAGE_PRESENT_AMD).frameIndex(amdFrame));
            AMDAntiLag.vkAntiLagUpdateAMD(device, antiLagData);
        }
    }

    /** Right after vkQueuePresentKHR (same id as the present); the next marker or submit of this iteration gets a fresh id. */
    static void presentEnd() {
        if (nv) {
            marker(NVLowLatency2.VK_LATENCY_MARKER_PRESENT_END_NV);
            framePresented = true;
        }
    }

    /** Left mouse button pressed in game: the flash of the Reflex latency analyzer / PC latency indicator. */
    public static void triggerFlash() {
        if (nv) {
            marker(NVLowLatency2.VK_LATENCY_MARKER_TRIGGER_FLASH_NV);
        }
    }

    private static void marker(int marker) {
        long sc = swapchain;
        if (sc == 0L) {
            return;
        }
        markerInfo.presentID(currentPresentId()).marker(marker);
        NVLowLatency2.vkSetLatencyMarkerNV(device, sc, markerInfo);
    }

    /**
     * The frame-pacing sleep, called where the software limiter would sleep (after the present, before the next frame samples input).
     * {@code capFps} is the cap in force (<= 0: none) and becomes {@code minimumIntervalUs} / {@code maxFPS}. Returns true when the
     * driver paced the frame (the caller then skips {@code FrameLimiter}); false = not active, use the software limiter.
     */
    public static boolean sleep(int capFps, boolean iconified) {
        if (!nv && !amd) {
            return false;
        }
        lastSleepActive = false;
        if (iconified) {
            return false; // nothing is presented: the driver has nothing to pace against, capMinimised applies
        }
        if (amd) {
            return amdSleep(capFps);
        }
        long sc = swapchain;
        if (sc == 0L) {
            return false;
        }
        int intervalUs = capFps > 0 ? 1_000_000 / capFps : 0;
        if (sleepModeDirty || intervalUs != appliedIntervalUs || mode != appliedMode) {
            sleepModeInfo.lowLatencyMode(mode != MODE_OFF).lowLatencyBoost(mode == MODE_BOOST).minimumIntervalUs(mode != MODE_OFF ? intervalUs : 0);
            int r = NVLowLatency2.vkSetLatencySleepModeNV(device, sc, sleepModeInfo);
            if (r != VK_SUCCESS) {
                disable("vkSetLatencySleepModeNV " + VkUtil.resultName(r));
                return false;
            }
            sleepModeDirty = false;
            appliedIntervalUs = intervalUs;
            appliedMode = mode;
        }
        if (mode == MODE_OFF) {
            return false;
        }
        long value = ++timelineValue;
        sleepInfo.value(value);
        int r = NVLowLatency2.vkLatencySleepNV(device, sc, sleepInfo);
        if (r != VK_SUCCESS) {
            disable("vkLatencySleepNV " + VkUtil.resultName(r));
            return false;
        }
        waitValues.put(0, value);
        r = VK12.vkWaitSemaphores(device, waitInfo, SLEEP_WAIT_TIMEOUT_NANOS);
        if (r == VK_TIMEOUT) {
            if (!warnedTimeout) {
                warnedTimeout = true;
                VkLog.LOGGER.warn("vkLatencySleepNV did not signal within {} ms (logged once)", SLEEP_WAIT_TIMEOUT_NANOS / 1_000_000L);
            }
        } else if (r != VK_SUCCESS) {
            disable("vkWaitSemaphores " + VkUtil.resultName(r));
            return false;
        }
        lastSleepActive = true;
        return true;
    }

    private static boolean amdSleep(int capFps) {
        if (mode == MODE_OFF) {
            if (!amdOffSent) {
                amdOffSent = true;
                antiLagData.mode(AMDAntiLag.VK_ANTI_LAG_MODE_OFF_AMD).maxFPS(0).pPresentationInfo(null);
                AMDAntiLag.vkAntiLagUpdateAMD(device, antiLagData);
            }
            return false;
        }
        amdOffSent = false;
        amdFrame++;
        amdMaxFps = Math.max(0, capFps);
        antiLagData.mode(AMDAntiLag.VK_ANTI_LAG_MODE_ON_AMD).maxFPS(amdMaxFps)
                .pPresentationInfo(antiLagPresent.stage(AMDAntiLag.VK_ANTI_LAG_STAGE_INPUT_AMD).frameIndex(amdFrame));
        AMDAntiLag.vkAntiLagUpdateAMD(device, antiLagData); // may block: the pacing (and the cap) of Anti-Lag
        lastSleepActive = true;
        return true;
    }

    /** True when the last {@link #sleep} paced the frame (the latency=low present wait is then redundant). */
    public static boolean sleepActive() {
        return lastSleepActive;
    }

    // ---------------------------------------------------------------------------------------------------------
    // runtime toggle and F3
    // ---------------------------------------------------------------------------------------------------------

    /** True when an extension is enabled, so the mode can be switched at run time. */
    public static boolean available() {
        return nv || amd;
    }

    /** off -> on -> boost -> off (AMD: off <-> on). Returns the new status text. */
    public static String cycleMode() {
        if (!available()) {
            return statusText();
        }
        mode = switch (mode) {
            case MODE_OFF -> MODE_ON;
            case MODE_ON -> nv ? MODE_BOOST : MODE_OFF;
            default -> MODE_OFF;
        };
        VkLog.LOGGER.info("Low latency mode switched: {}", statusText());
        return statusText();
    }

    public static int mode() {
        return mode;
    }

    /** "off | on | boost | unsupported (reason)" plus the vendor. */
    public static String statusText() {
        if (!available()) {
            return "reflex=off".equals(unsupportedReason) ? "off (config reflex=off, restart to enable)" : "unsupported (" + unsupportedReason + ")";
        }
        String m = mode == MODE_OFF ? "off" : mode == MODE_BOOST ? "boost" : "on";
        return m + (nv ? " (NVIDIA VK_NV_low_latency2)" : " (AMD Anti-Lag)");
    }

    /**
     * Averages of the driver's latency reports (vkGetLatencyTimingsNV, up to 64 recent frames), in microseconds:
     * {@code out[0]} simulation start -> present end, {@code out[1]} render submit start -> present end, {@code out[2]} simulation
     * start -> GPU render end, {@code out[3]} GPU render time. Returns the number of frames averaged (0 = no data; NVIDIA only).
     * Call a few times per second at most (render thread).
     */
    public static int latencyTimings(double[] out) {
        if (!nv || swapchain == 0L) {
            return 0;
        }
        timingsInfo.pTimings(timings).timingCount(MAX_TIMINGS);
        NVLowLatency2.vkGetLatencyTimingsNV(device, swapchain, timingsInfo);
        int count = Math.min(timingsInfo.timingCount(), MAX_TIMINGS);
        long base = timings.address();
        double simPresent = 0.0;
        double submitPresent = 0.0;
        double simGpu = 0.0;
        double gpu = 0.0;
        int n = 0;
        for (int i = 0; i < count; i++) {
            long a = base + (long) i * VkLatencyTimingsFrameReportNV.SIZEOF;
            long simStart = VkLatencyTimingsFrameReportNV.nsimStartTimeUs(a);
            long submitStart = VkLatencyTimingsFrameReportNV.nrenderSubmitStartTimeUs(a);
            long presentEnd = VkLatencyTimingsFrameReportNV.npresentEndTimeUs(a);
            long gpuStart = VkLatencyTimingsFrameReportNV.ngpuRenderStartTimeUs(a);
            long gpuEnd = VkLatencyTimingsFrameReportNV.ngpuRenderEndTimeUs(a);
            if (simStart == 0L || submitStart == 0L || presentEnd < simStart || presentEnd < submitStart) {
                continue;
            }
            simPresent += presentEnd - simStart;
            submitPresent += presentEnd - submitStart;
            if (gpuEnd >= simStart && gpuEnd >= gpuStart && gpuStart != 0L) {
                simGpu += gpuEnd - simStart;
                gpu += gpuEnd - gpuStart;
            }
            n++;
        }
        if (n == 0) {
            return 0;
        }
        out[0] = simPresent / n;
        out[1] = submitPresent / n;
        out[2] = simGpu / n;
        out[3] = gpu / n;
        return n;
    }
}
