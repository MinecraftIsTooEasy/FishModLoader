package net.xiaoyu233.fml.vulkan.core;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.render.gpu.CommandStream;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearRect;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan core (module A): instance/device/VMA, swapchain, frames in flight, the offscreen "default framebuffer", per-frame streams
 * and uploads. Render thread only.
 * <p>
 * Frame model: the game records into one main command buffer (+ one upload command buffer that is submitted first) of the current
 * frame slot; {@link #present()} blits the offscreen target to the swapchain, submits both buffers, presents and moves to the next slot
 * (waiting for that slot's fence from {@code framesInFlight} frames ago). Everything that is recorded is rendered into a fixed
 * offscreen colour/depth target with one render pass that is begun lazily and resumed (loadOp LOAD) after every interruption.
 */
public final class RenderCore {
    private static final int VERTEX_CHUNK_BYTES = 8 * 1024 * 1024;
    private static final int INDEX_CHUNK_BYTES = 8 * 1024 * 1024;
    private static final int UNIFORM_CHUNK_BYTES = 1024 * 1024;
    private static final int STAGING_CHUNK_BYTES = 16 * 1024 * 1024;
    private static final long ACQUIRE_TIMEOUT_NANOS = 1_000_000_000L;
    private static final int STATS_INTERVAL_FRAMES = 600;
    /** Debug mode: frames per "Vulkan perf" log line (frame times, draw statistics, memory). */
    private static final int PERF_INTERVAL_FRAMES = 300;
    private static final int READBACK_GRANULARITY = 256 * 1024;

    // device (see DeviceSetup)
    private static DeviceSetup.Context ctx;
    private static VkInstance instance;
    private static VkPhysicalDevice physicalDevice;
    private static VkDevice device;
    private static VkQueue queue;
    private static int queueFamilyIndex;
    private static long allocator;
    private static long surface;
    private static DeviceInfo deviceInfo;
    private static boolean uma;
    private static int maxTargetDimension;
    private static boolean initialized;
    private static boolean debug;

    // formats / render passes / offscreen target
    private static int colorFormat;
    private static int depthFormat;
    private static boolean linearBlit;
    private static long renderPassLoad;
    private static long renderPassClear;
    private static OffscreenTarget target;
    /** Target the game draws into right now: null = the MAIN target (see {@link #setActiveTarget}). */
    private static OffscreenTarget activeTarget;
    private static DeviceCaps deviceCaps = DeviceCaps.NONE;
    private static boolean lowLatency;

    // window / swapchain state
    private static int windowWidth;
    private static int windowHeight;
    private static Swapchain swapchain;
    private static boolean swapchainDirty;
    /** macOS with vsync off: IOSurface/CALayer presentation instead of the CAMetalLayer swapchain (see LayerPresenter); null otherwise. */
    private static LayerPresenter presenter;
    private static long glfwWindowHandle;
    private static final boolean MAC = org.lwjgl.system.Platform.get() == org.lwjgl.system.Platform.MACOSX;
    /** Frames handed to the display (swapchain present or layer publish), and the nanoTime of the last PRESENT_RING ones. */
    private static final int PRESENT_RING = 8192;
    private static final long[] PRESENT_AT = new long[PRESENT_RING];
    private static volatile long presentCount;
    private static boolean vsyncSet;
    private static boolean vsyncRequested;

    // frames
    private static int framesInFlight;
    private static FrameSlot[] slots;
    private static int currentSlot;
    private static long frameNumber;
    private static boolean inRenderPass;
    private static boolean endingPass;
    private static VkCommandBuffer mainCommandBuffer;
    private static VkCommandBuffer uploadCommandBuffer;
    private static long flushFence;

    // streams of the current slot (hot path)
    private static StreamAllocator vertexStream;
    private static StreamAllocator indexStream;
    private static StreamAllocator uniformStream;
    private static StreamAllocator stagingStream;

    // listeners (copy on write, iterated by index: no allocation per notification)
    private static FrameListener[] listeners = new FrameListener[0];

    // GPU-resident buffers that are still alive (released at shutdown if the owner never frees them)
    private static final HashSet<StaticRelease> staticBuffers = new HashSet<>();

    // readback buffer of readColor (host cached, grown on demand)
    private static long readbackBuffer;
    private static long readbackAllocation;
    private static long readbackAddress;
    private static long readbackCapacity;
    private static boolean readbackCoherent;
    private static boolean readbackOverflowLogged;

    private static FrameCapture capture;

    // debug perf sampler (see perfSample)
    private static long perfLastPresent;
    private static long perfFrameBegin;
    private static int perfCount;
    private static final float[] perfFrameMs = new float[PERF_INTERVAL_FRAMES];
    private static double perfCpuMs;
    private static double perfWaitMs;
    private static long perfDraws;
    private static long perfIndexed;
    private static long perfPipelineBinds;
    private static long perfDescriptorBinds;
    private static long perfPushUpdates;
    private static int perfMaxDraws;
    private static long perfStallFrames;

    private RenderCore() {
    }

    private static final class FrameSlot {
        long fence;
        long commandPool;
        long uploadCommandPool;
        VkCommandBuffer main;
        VkCommandBuffer upload;
        long imageAvailable;
        StreamAllocator vertex;
        StreamAllocator index;
        StreamAllocator uniform;
        StreamAllocator staging;
        final ArrayList<Runnable> deletions = new ArrayList<>();
    }

    // ---------------------------------------------------------------------------------------------------------
    // lifecycle (VulkanBackend / module E)
    // ---------------------------------------------------------------------------------------------------------

    public static void init(long glfwWindow, int fbWidth, int fbHeight) {
        if (initialized) {
            VkLog.LOGGER.warn("RenderCore.init() called while already initialized, ignored");
            return;
        }
        try {
            lowLatency = "low".equals(VulkanConfig.latency());
            framesInFlight = lowLatency ? 1 : VulkanConfig.framesInFlight();
            if (!lowLatency && MAC && "auto".equals(VulkanConfig.macPresenter()) && !VulkanConfig.framesInFlightSet()) {
                // measured (docs/runtime-notes.md, NR-29 v3): with the layer presenter 3 frames in flight give +30..60% presented FPS and about
                // 1.6x the 1% low over 2 (MoltenVK's completion latency is hidden); under vsync the drawable wait bounds the queue anyway
                framesInFlight = 3;
            }
            activeTarget = null;
            debug = VulkanConfig.debug();
            windowWidth = fbWidth;
            windowHeight = fbHeight;
            frameNumber = 0;
            currentSlot = 0;
            inRenderPass = false;
            endingPass = false;
            swapchainDirty = false;

            glfwWindowHandle = glfwWindow;
            ctx = DeviceSetup.create(glfwWindow);
            instance = ctx.instance;
            physicalDevice = ctx.physicalDevice;
            device = ctx.device;
            queue = ctx.queue;
            queueFamilyIndex = ctx.queueFamily;
            allocator = ctx.allocator;
            surface = ctx.surface;
            deviceInfo = ctx.info;
            uma = ctx.uma;
            deviceCaps = ctx.caps;
            maxTargetDimension = ctx.maxTargetDimension;

            chooseFormats();
            createRenderPasses();
            createFrameResources();
            if (usePresenter()) {
                presenter = LayerPresenter.create(device, glfwWindow, windowWidth, windowHeight, presenterSurfaces());
            }
            if (presenter == null) {
                swapchain = Swapchain.create(surface, windowWidth, windowHeight, effectiveVsync(), null);
            }
            VkLog.LOGGER.info("Presentation: {}, {} images (latency low: {}, present limiter {})", presentationName(), swapchainImageCount(),
                    lowLatency, PRESENT_MIN_MS > 0 ? PRESENT_MIN_MS + " ms (fml.vulkan.presentMinMs)" : "off");

            int width = clampTargetSize(fbWidth);
            int height = clampTargetSize(fbHeight);
            target = OffscreenTarget.create(width, height, colorFormat, depthFormat, renderPassLoad);
            selectSlot(0);
            beginCommandBuffers(slots[0]);
            initializeTarget();

            int[] captureFrames = VulkanConfig.captureFrames();
            capture = new FrameCapture(captureFrames, VulkanConfig.captureDir(), colorFormat == VK_FORMAT_B8G8R8A8_UNORM);
            if (captureFrames.length > 0) {
                VkLog.LOGGER.info("Frame capture enabled for frames {} -> {}", Arrays.toString(captureFrames), VulkanConfig.captureDir());
            }

            initialized = true;
            VkLog.LOGGER.info("RenderCore initialized: offscreen target {}x{} (color format {}, depth format {}), {} frames in flight{}",
                    width, height, colorFormat, depthFormat, framesInFlight, uma ? ", unified memory static buffers" : "");
        } catch (Throwable t) {
            destroyAll();
            throw t;
        }
    }

    public static void shutdown() {
        if (!initialized && ctx == null) {
            return;
        }
        destroyAll();
        VkLog.LOGGER.info("RenderCore shutdown complete");
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Window framebuffer size changed (module E, from glfwPollEvents inside Display.update). The swapchain is recreated and the offscreen
     * target resized at the NEXT {@link #present()}, which matches the game: it only learns the new size after this frame and issues
     * its viewport with the new size in the next one. A size of 0 (minimised) keeps the current target and skips presentation.
     */
    public static void onFramebufferResized(int fbWidth, int fbHeight) {
        if (fbWidth == windowWidth && fbHeight == windowHeight) {
            return;
        }
        windowWidth = fbWidth;
        windowHeight = fbHeight;
        swapchainDirty = true;
    }

    public static void setVSync(boolean vsync) {
        boolean before = effectiveVsync();
        vsyncSet = true;
        vsyncRequested = vsync;
        if (initialized && effectiveVsync() != before) {
            swapchainDirty = true; // present mode follows vsync, applied when the swapchain is recreated
        }
    }

    private static boolean effectiveVsync() {
        if (VulkanConfig.vsyncForced()) {
            return VulkanConfig.vsync();
        }
        return vsyncSet ? vsyncRequested : VulkanConfig.vsync();
    }

    /**
     * Test harness: writes a presented frame to {@code <captureDir>/<name>.png}; {@code delay} 0 = the next presented frame, 1 = the one
     * after it (MITE presents at the start of the loop iteration, so the frame a script step has influenced is the second one).
     */
    public static void requestCapture(String name, int delay) {
        FrameCapture c = capture;
        if (c != null) {
            c.request(name, delay);
        }
    }

    /**
     * Display.update: ends the frame (listeners), blits the offscreen colour to the swapchain, submits upload + main command buffers,
     * presents, then moves to the next frame slot (fence wait, deferred deletions, stream reset, fresh command buffers).
     */
    /**
     * Opt-in presentation limiter (fml.vulkan.presentMinMs > 0, vsync off): frames that arrive sooner than this after the last presented one
     * are rendered but not presented. OFF by default: every frame is presented.
     */
    private static final double PRESENT_MIN_MS = VulkanConfig.presentMinMs();
    private static long lastPresentStart;
    private static final long[] PX = new long[6];
    public static void present() {
        if (!initialized) {
            return;
        }
        FrameSlot slot = slots[currentSlot];
        long frame = frameNumber;

        fireBeforeFrameEnd();
        ensureNoRenderPass();
        fireBeforePresent();

        long pT0 = System.nanoTime();
        if (presenter != null) {
            presenter.publishCompleted(); // the previous frame is usually done by now: hand it to the compositor right away
        }
        boolean capturing = capture != null && capture.isDue(frame);
        boolean gated = false;
        if (PRESENT_MIN_MS > 0 && !capturing && !swapchainDirty && windowWidth > 0 && windowHeight > 0 && !effectiveVsync()) {
            gated = pT0 - lastPresentStart < (long) (PRESENT_MIN_MS * 1e6);
        }
        int imageIndex = gated ? -1 : acquireImage(slot);
        long pT1 = System.nanoTime();
        recordPresentCommands(imageIndex, capturing, frame);

        endUploadCommandBuffer();
        VkUtil.check(vkEndCommandBuffer(mainCommandBuffer), "vkEndCommandBuffer (main)");
        flushStreams(slot);
        long pT2 = System.nanoTime();
        submit(slot, imageIndex);
        long pT3 = System.nanoTime();
        if (imageIndex >= 0) {
            presentImage(slot, imageIndex);
            lastPresentStart = pT1;
        } else if (!gated) {
            LockSupport.parkNanos(8_000_000L); // nothing is shown (minimised / occluded window): do not spin
        }

        if (debug && frame > 0 && frame % STATS_INTERVAL_FRAMES == 0) {
            logStats(slot, frame);
        }
        if (debug) {
            perfSample(frame);
        }

        long waitStart = System.nanoTime();
        nextFrame();
        {
            long pT5 = System.nanoTime();
            net.xiaoyu233.fml.vulkan.render.frame.FrameStats.noteFrameTiming(pT3 - pT2, imageIndex >= 0, pT5 - waitStart);
            PX[0] += pT1 - pT0; PX[1] += pT2 - pT1; PX[2] += pT3 - pT2; PX[3] += waitStart - pT3; PX[4] += pT5 - waitStart; PX[5]++;
            if (PX[5] == 600) {
                VkLog.LOGGER.info("PROF per-frame ms: acquire {} record+flush {} submit {} present {} nextFrame(fence) {}", PX[0] / 6.0e8, PX[1] / 6.0e8, PX[2] / 6.0e8, PX[3] / 6.0e8, PX[4] / 6.0e8);
                java.util.Arrays.fill(PX, 0L);
            }
        }
        if (debug) {
            perfFrameBegin = System.nanoTime();
            perfWaitMs += (perfFrameBegin - waitStart) / 1.0e6;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // device
    // ---------------------------------------------------------------------------------------------------------

    public static VkInstance instance() {
        return instance;
    }

    public static VkPhysicalDevice physicalDevice() {
        return physicalDevice;
    }

    public static VkDevice device() {
        return device;
    }

    public static VkQueue queue() {
        return queue;
    }

    public static int queueFamily() {
        return queueFamilyIndex;
    }

    public static long allocator() {
        return allocator;
    }

    public static DeviceInfo deviceInfo() {
        return deviceInfo;
    }

    /** Optional capabilities that were enabled on the device (multi-draw-indirect, extended dynamic state, present wait, timestamps). */
    public static DeviceCaps deviceCaps() {
        return deviceCaps;
    }

    /** True on unified-memory devices while static buffers use the direct host write path (may turn false at run time, see createStatic). */
    public static boolean isUnifiedMemory() {
        return uma;
    }

    /** Frames in flight actually in use (fml.vulkan.framesInFlight, or 1 with fml.vulkan.latency=low). */
    public static int framesInFlight() {
        return framesInFlight;
    }

    /** fml.vulkan.latency=low: one frame in flight and the minimum swapchain image count. */
    public static boolean isLowLatency() {
        return lowLatency;
    }

    /** Number of images of the current swapchain (0 before the first one exists or while minimised). */
    public static int swapchainImageCount() {
        LayerPresenter lp = presenter;
        if (lp != null) {
            return lp.poolSize();
        }
        Swapchain sc = swapchain;
        return sc == null ? 0 : sc.images.length;
    }

    /** How frames reach the display right now, e.g. "swapchain IMMEDIATE" or "layer presenter (IOSurface)"; for logs and the bench. */
    public static String presentationName() {
        LayerPresenter lp = presenter;
        if (lp != null) {
            return "layer presenter (IOSurface -> CALayer contents, " + lp.poolSize() + " surfaces)";
        }
        Swapchain sc = swapchain;
        return sc == null ? "none" : "swapchain " + Swapchain.presentModeName(sc.presentMode);
    }

    /** Number of frames handed to the display so far (swapchain presents or layer publishes). Any thread. */
    public static long presentCount() {
        return presentCount;
    }

    /** nanoTime at which present number {@code index} (0-based) was handed to the display; 0 when it fell out of the ring. Any thread. */
    public static long presentTime(long index) {
        long n = presentCount;
        if (index < 0 || index >= n || index < n - PRESENT_RING + 64) {
            return 0L;
        }
        return PRESENT_AT[(int) (index % PRESENT_RING)];
    }

    /** Render thread: a frame was handed to the display at {@code nanos}. */
    static void notePresent(long nanos) {
        long n = presentCount;
        PRESENT_AT[(int) (n % PRESENT_RING)] = nanos;
        presentCount = n + 1;
    }

    /**
     * Blocks until the most recent presented frame has reached the screen (VK_KHR_present_wait), at most {@code timeoutNanos}. Returns
     * false without waiting when the extension is not enabled, nothing was presented yet or the wait timed out / failed. Render thread.
     */
    public static boolean waitForPresent(long timeoutNanos) {
        Swapchain sc = swapchain;
        if (!initialized || sc == null || !deviceCaps.presentWait()) {
            return false;
        }
        return sc.waitForLastPresent(timeoutNanos);
    }

    /** The MAIN offscreen target (replaced when the window is resized: do not cache). */
    public static OffscreenTarget mainTarget() {
        return target;
    }

    /** The target draws currently go to (MAIN unless {@link #setActiveTarget} selected another one). */
    public static OffscreenTarget activeTarget() {
        OffscreenTarget t = activeTarget;
        return t != null ? t : target;
    }

    /** Width of the target draws currently go to; {@link #framebufferWidth()} stays the MAIN size. */
    public static int activeWidth() {
        return activeTarget().width;
    }

    public static int activeHeight() {
        return activeTarget().height;
    }

    /**
     * Redirects draws, clears and readbacks to {@code t} (null = MAIN). The running render pass is ended first (pending merged draws are
     * recorded into the old target); the next draw begins a LOAD pass on the new one. Targets must come from
     * {@link #createTarget}; one may be selected only while it is alive. Listeners are told through {@code onCommandBufferReset}-free
     * means: viewport and scissor are marked dirty by the caller ({@code CommandStream.invalidate}).
     */
    public static void setActiveTarget(OffscreenTarget t) {
        OffscreenTarget wanted = t == target ? null : t;
        if (wanted == activeTarget) {
            return;
        }
        ensureNoRenderPass();
        activeTarget = wanted;
        CommandStream.onTargetChanged();
    }

    /**
     * Creates an extra colour+depth target (clamped to the device limit) that is compatible with {@link #renderPass()}. Its images are
     * brought to their attachment layout by an empty CLEAR pass recorded into this frame's upload command buffer, so the running pass of
     * the main command buffer is not interrupted and the target is usable by every command recorded afterwards.
     */
    public static OffscreenTarget createTarget(int width, int height) {
        OffscreenTarget t = OffscreenTarget.create(clampTargetSize(width), clampTargetSize(height), colorFormat, depthFormat, renderPassLoad);
        try {
            beginPass(uploadCommandBuffer, t, renderPassClear, true, 0f, 0f, 0f, 1f, 1f);
            vkCmdEndRenderPass(uploadCommandBuffer);
        } catch (Throwable e) {
            t.destroy();
            throw e;
        }
        return t;
    }

    /** Destroys a target made by {@link #createTarget} once the frames in flight that may use it have completed. */
    public static void destroyTarget(OffscreenTarget t) {
        if (t == null || t == target) {
            return;
        }
        if (activeTarget == t) {
            setActiveTarget(null);
        }
        deferDestroy(t::destroy);
    }

    /** The CLEAR render pass (clears both attachments, initial layout UNDEFINED); compatible with {@link #renderPass()}. */
    public static long renderPassClear() {
        return renderPassClear;
    }

    // ---------------------------------------------------------------------------------------------------------
    // offscreen "default framebuffer"
    // ---------------------------------------------------------------------------------------------------------

    /** Size of the real offscreen target (changes only inside {@link #present()}, together with {@code FrameListener.onResize}). */
    public static int framebufferWidth() {
        return target.width;
    }

    public static int framebufferHeight() {
        return target.height;
    }

    public static int colorFormat() {
        return colorFormat;
    }

    public static int depthFormat() {
        return depthFormat;
    }

    public static long renderPass() {
        return renderPassLoad;
    }

    // ---------------------------------------------------------------------------------------------------------
    // command recording
    // ---------------------------------------------------------------------------------------------------------

    public static VkCommandBuffer mainCommandBuffer() {
        return mainCommandBuffer;
    }

    public static VkCommandBuffer uploadCommandBuffer() {
        return uploadCommandBuffer;
    }

    public static boolean inRenderPass() {
        return inRenderPass;
    }

    public static void ensureRenderPass() {
        if (inRenderPass) {
            return;
        }
        beginPass(renderPassLoad, false, 0f, 0f, 0f, 0f, 0f);
    }

    public static void ensureNoRenderPass() {
        if (endingPass) {
            return; // called from a beforePassEnd listener: the outer call ends the pass after the listeners have finished
        }
        endingPass = true;
        try {
            fireBeforePassEnd();
        } finally {
            endingPass = false;
        }
        endPass();
    }

    public static long frameNumber() {
        return frameNumber;
    }

    public static int frameSlot() {
        return currentSlot;
    }

    /**
     * Mid-frame synchronisation point: ends the pass, submits upload + main command buffers, waits until the GPU is done and begins
     * fresh command buffers for the same frame (stream / staging memory of this frame stays valid). Because the wait covers the whole
     * queue, every resource whose destruction was deferred so far is released here.
     */
    public static void flushAndWait() {
        if (!initialized) {
            return;
        }
        ensureNoRenderPass();
        FrameSlot slot = slots[currentSlot];

        endUploadCommandBuffer();
        VkUtil.check(vkEndCommandBuffer(mainCommandBuffer), "vkEndCommandBuffer (flushAndWait main)");
        flushStreams(slot);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(uploadCommandBuffer, mainCommandBuffer));
            VkUtil.check(vkResetFences(device, flushFence), "vkResetFences (flushAndWait)");
            VkUtil.check(vkQueueSubmit(queue, submitInfo, flushFence), "vkQueueSubmit (flushAndWait)");
        }
        VkUtil.waitFence(device, flushFence, "flushAndWait");
        VkUtil.check(vkQueueWaitIdle(queue), "vkQueueWaitIdle (flushAndWait)");

        for (FrameSlot s : slots) {
            runDeletions(s.deletions);
        }
        if (capture != null) {
            capture.collectAll();
        }

        VkUtil.check(vkResetCommandPool(device, slot.commandPool, 0), "vkResetCommandPool (flushAndWait)");
        VkUtil.check(vkResetCommandPool(device, slot.uploadCommandPool, 0), "vkResetCommandPool (flushAndWait upload)");
        beginCommandBuffers(slot);
        fireCommandBufferReset();
    }

    public static void addListener(FrameListener l) {
        if (l == null) {
            return;
        }
        FrameListener[] old = listeners;
        for (FrameListener existing : old) {
            if (existing == l) {
                return;
            }
        }
        FrameListener[] updated = Arrays.copyOf(old, old.length + 1);
        updated[old.length] = l;
        listeners = updated;
    }

    public interface FrameListener {
        default void onFrameBegin() {}
        default void onCommandBufferReset() {}
        default void beforePassEnd() {}
        default void beforeFrameEnd() {}
        default void onResize(int w, int h) {}
        /** The frame's drawing is complete (render pass ended, every pending draw recorded); the present commands follow. Render thread. */
        default void beforePresent() {}
    }

    // ---------------------------------------------------------------------------------------------------------
    // memory
    // ---------------------------------------------------------------------------------------------------------

    public static StreamSlice streamVertex(int bytes) {
        return vertexStream.allocate(bytes);
    }

    /** Stream slice that may be used as the buffer of drawIndexedIndirect (the vertex stream carries INDIRECT_BUFFER usage). */
    public static StreamSlice streamIndirect(int bytes) {
        return vertexStream.allocate(bytes);
    }

    public static StreamSlice streamIndex(int bytes) {
        return indexStream.allocate(bytes);
    }

    public static StreamSlice streamUniform(int bytes) {
        return uniformStream.allocate(bytes);
    }

    public static StreamSlice streamStaging(int bytes) {
        return stagingStream.allocate(bytes);
    }

    /**
     * GPU-resident copy of {@code data} (position..limit; the buffer's position is not modified). On unified memory (integrated /
     * Apple GPUs whose whole memory is host visible) the data is written straight into host-visible, coherent memory (device-local
     * preferred); otherwise, and whenever that memory turns out to be unusable, it is staged through the upload command buffer into
     * a device-local buffer. The result is usable by commands recorded after this call.
     */
    public static StaticBuffer createStatic(ByteBuffer data, int usage) {
        if (slots == null) {
            throw new IllegalStateException("RenderCore is not initialized");
        }
        int size = data.remaining();
        if (uma) {
            try {
                return createStaticUnified(data, usage, size);
            } catch (UnifiedMemoryUnavailable e) {
                // e.g. an integrated GPU with a small "visible VRAM" window next to its big device-local heap: use the device-local heap
                StaticBuffer staged = createStaticStaged(data, usage, size); // throws when this fails as well: nothing is changed then
                uma = false;
                VkLog.LOGGER.warn("Unified memory is not usable for static buffers ({}); static buffers are staged through device-local "
                        + "memory from now on", e.getMessage());
                return staged;
            }
        }
        return createStaticStaged(data, usage, size);
    }

    /** The unified memory path cannot serve a request (out of memory in the host-visible heaps / not mappable); the staged path can. */
    private static final class UnifiedMemoryUnavailable extends Exception {
        private static final long serialVersionUID = 1L;

        UnifiedMemoryUnavailable(String message) {
            super(message, null, false, false); // control flow of a rare path: no stack trace
        }
    }

    /**
     * Unified memory path: host-visible + host-coherent memory with DEVICE_LOCAL only preferred, so that VMA continues with the other
     * host-visible memory types when a small device-local window is exhausted (instead of failing the allocation).
     */
    private static StaticBuffer createStaticUnified(ByteBuffer data, int usage, int size) throws UnifiedMemoryUnavailable {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(Math.max(size, 16)) // Vulkan buffers must not be empty
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT)
                    .requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                    .preferredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
            int result = Vma.vmaCreateBuffer(allocator, bufferInfo, allocationInfo, pBuffer, pAllocation, info);
            if (result == VK_ERROR_OUT_OF_DEVICE_MEMORY || result == VK_ERROR_FEATURE_NOT_PRESENT || result == VK_ERROR_MEMORY_MAP_FAILED) {
                throw new UnifiedMemoryUnavailable("vmaCreateBuffer of " + size + " bytes: " + VkUtil.resultName(result));
            }
            VkUtil.check(result, "vmaCreateBuffer (static, " + size + " bytes)");
            long buffer = pBuffer.get(0);
            long allocation = pAllocation.get(0);

            long mapped = info.pMappedData();
            if (mapped == 0) {
                Vma.vmaDestroyBuffer(allocator, buffer, allocation);
                throw new UnifiedMemoryUnavailable("the " + size + " byte buffer is not host mapped");
            }
            try {
                if (size > 0) {
                    MemoryUtil.memByteBuffer(mapped, size).put(data.duplicate());
                }
            } catch (Throwable t) {
                Vma.vmaDestroyBuffer(allocator, buffer, allocation);
                throw t;
            }
            return registerStatic(buffer, allocation, size);
        }
    }

    /** Device-local buffer filled by a copy recorded into the upload command buffer (source: this frame's staging stream). */
    private static StaticBuffer createStaticStaged(ByteBuffer data, int usage, int size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(Math.max(size, 16)) // Vulkan buffers must not be empty
                    .usage(usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            VkUtil.check(Vma.vmaCreateBuffer(allocator, bufferInfo, allocationInfo, pBuffer, pAllocation, null),
                    "vmaCreateBuffer (static, " + size + " bytes)");
            long buffer = pBuffer.get(0);
            long allocation = pAllocation.get(0);

            try {
                if (size > 0) {
                    StreamSlice staging = streamStaging(size);
                    staging.mapped().put(data.duplicate());
                    VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                            .srcOffset(staging.offset())
                            .dstOffset(0)
                            .size(size);
                    vkCmdCopyBuffer(uploadCommandBuffer, staging.buffer(), buffer, region);
                }
            } catch (Throwable t) {
                Vma.vmaDestroyBuffer(allocator, buffer, allocation);
                throw t;
            }
            return registerStatic(buffer, allocation, size);
        }
    }

    private static StaticBuffer registerStatic(long buffer, long allocation, int size) {
        StaticRelease release = new StaticRelease(buffer, allocation);
        staticBuffers.add(release);
        return new StaticBuffer(buffer, 0, size, release);
    }

    /** Release hook of a {@link StaticBuffer}: queues the VMA buffer for destruction, or destroys it at shutdown. */
    private static final class StaticRelease implements Runnable {
        final long buffer;
        final long allocation;

        StaticRelease(long buffer, long allocation) {
            this.buffer = buffer;
            this.allocation = allocation;
        }

        @Override
        public void run() {
            if (!staticBuffers.remove(this)) {
                return; // already destroyed by shutdown
            }
            deferDestroy(() -> Vma.vmaDestroyBuffer(allocator, buffer, allocation));
        }

        void destroyNow() {
            Vma.vmaDestroyBuffer(allocator, buffer, allocation);
        }
    }

    public static void deferDestroy(Runnable r) {
        FrameSlot[] frameSlots = slots;
        if (r == null || frameSlots == null) {
            return; // RenderCore is gone: whatever r would destroy died with the device
        }
        frameSlots[currentSlot].deletions.add(r);
    }

    // ---------------------------------------------------------------------------------------------------------
    // framebuffer operations
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Clears the colour and/or depth attachment inside the rectangle (Vulkan coordinates, clipped to the target; empty rectangles are
     * ignored). The framebuffer is alpha-less like a GL window without destination alpha: colour clears always write alpha 1.
     * vkCmdClearAttachments is not affected by glColorMask, the caller applies it. A full colour + depth clear that is not inside a render
     * pass begins the CLEAR render pass instead (no attachment load, no clear command).
     */
    public static void clear(boolean color, boolean depth, float r, float g, float b, float a, float depthValue,
                             int x, int y, int w, int h) {
        if (!color && !depth) {
            return;
        }
        OffscreenTarget t = activeTarget();
        long x0 = Math.max(0L, x);
        long y0 = Math.max(0L, y);
        long x1 = Math.min((long) x + w, t.width);
        long y1 = Math.min((long) y + h, t.height);
        if (x1 <= x0 || y1 <= y0) {
            return;
        }

        if (color && depth && x0 == 0 && y0 == 0 && x1 == t.width && y1 == t.height) {
            ensureNoRenderPass(); // records pending merged draws (they are overwritten) and ends the running pass
            beginPass(renderPassClear, true, r, g, b, 1.0f, depthValue);
            return;
        }

        fireBeforePassEnd(); // pending merged draws must be in the command buffer before the clear
        ensureRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int count = (color ? 1 : 0) + (depth ? 1 : 0);
            VkClearAttachment.Buffer attachments = VkClearAttachment.calloc(count, stack);
            int i = 0;
            if (color) {
                attachments.position(i++);
                attachments.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(0);
                attachments.clearValue().color().float32(0, r).float32(1, g).float32(2, b).float32(3, 1.0f);
            }
            if (depth) {
                attachments.position(i);
                attachments.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                attachments.clearValue().depthStencil().depth(depthValue).stencil(0);
            }
            attachments.position(0);

            VkClearRect.Buffer rect = VkClearRect.calloc(1, stack);
            rect.rect().offset().set((int) x0, (int) y0);
            rect.rect().extent().set((int) (x1 - x0), (int) (y1 - y0));
            rect.baseArrayLayer(0).layerCount(1);
            vkCmdClearAttachments(mainCommandBuffer, attachments, rect);
        }
    }

    /**
     * glReadPixels: synchronous (submits and waits). Writes the clipped rectangle as tightly packed RGBA8, rows bottom-up (GL order),
     * into {@code dst} starting at its position (position and limit are not modified); pixels outside the framebuffer are left untouched.
     * Alpha reads as 255 (alpha-less framebuffer).
     */
    public static void readColor(int glX, int glY, int w, int h, ByteBuffer dst) {
        if (!initialized || w <= 0 || h <= 0 || dst == null) {
            return;
        }
        OffscreenTarget t = activeTarget();
        long x0 = Math.max(0L, glX);
        long y0 = Math.max(0L, glY);
        long x1 = Math.min((long) glX + w, t.width);
        long y1 = Math.min((long) glY + h, t.height);
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        int copyWidth = (int) (x1 - x0);
        int copyHeight = (int) (y1 - y0);
        long bytes = (long) copyWidth * copyHeight * 4;
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("readColor region too large: " + copyWidth + "x" + copyHeight);
        }

        ensureNoRenderPass();
        ensureReadbackBuffer(bytes);
        VkCommandBuffer cb = mainCommandBuffer;
        VkUtil.imageBarrier(cb, t.colorImage, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            // GL row 0 is the bottom row, image row 0 the top row
            region.imageOffset().set((int) x0, t.height - (int) y1, 0);
            region.imageExtent().set(copyWidth, copyHeight, 1);
            vkCmdCopyImageToBuffer(cb, t.colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readbackBuffer, region);

            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(readbackBuffer)
                    .offset(0)
                    .size(VK_WHOLE_SIZE);
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
        }
        VkUtil.imageBarrier(cb, t.colorImage, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        flushAndWait();

        if (!readbackCoherent) {
            Vma.vmaInvalidateAllocation(allocator, readbackAllocation, 0, bytes);
        }
        ByteBuffer src = MemoryUtil.memByteBuffer(readbackAddress, (int) bytes).order(ByteOrder.LITTLE_ENDIAN);
        boolean swapRedBlue = colorFormat == VK_FORMAT_B8G8R8A8_UNORM;
        int pixels = copyWidth * copyHeight;
        for (int i = 0; i < pixels; i++) {
            int v = src.getInt(i << 2) | 0xFF000000; // little endian read of bytes R,G,B,A: alpha is the top byte
            if (swapRedBlue) {
                v = (v & 0xFF00FF00) | ((v & 0xFF) << 16) | ((v >>> 16) & 0xFF);
            }
            src.putInt(i << 2, v);
        }

        int base = dst.position();
        int rowBytes = copyWidth * 4;
        int dstStride = w * 4;
        int columnOffset = (int) (x0 - glX) * 4;
        int firstRow = (int) (y0 - glY);
        for (int j = 0; j < copyHeight; j++) {
            int dstIndex = base + (firstRow + j) * dstStride + columnOffset;
            if (dstIndex + rowBytes > dst.limit()) {
                if (!readbackOverflowLogged) {
                    readbackOverflowLogged = true;
                    VkLog.LOGGER.warn("readColor: destination buffer too small for {}x{} pixels ({} bytes remaining), image truncated",
                            w, h, dst.remaining());
                }
                break;
            }
            dst.put(dstIndex, src, (copyHeight - 1 - j) * rowBytes, rowBytes); // buffer row 0 is the top row of the region
        }
    }

    /**
     * glCopyTexSubImage2D: copies the framebuffer rectangle (GL coordinates, clipped to the framebuffer) into mip {@code dstMip} of
     * {@code dstImage} at ({@code dstX}, {@code dstY}); GL's bottom-up rows become top-down texture rows (vertical flip). The caller
     * ended the pass and moved {@code dstImage} to TRANSFER_DST_OPTIMAL; it stays there.
     */
    public static void blitColorToImage(long dstImage, int dstMip, int dstX, int dstY, int glSrcX, int glSrcY, int w, int h) {
        blitColorToImage(activeTarget(), dstImage, dstMip, dstX, dstY, glSrcX, glSrcY, w, h);
    }

    /** {@link #blitColorToImage(long, int, int, int, int, int, int, int)} from an explicit source target instead of the active one. */
    public static void blitColorToImage(OffscreenTarget t, long dstImage, int dstMip, int dstX, int dstY, int glSrcX, int glSrcY, int w, int h) {
        if (!initialized || w <= 0 || h <= 0) {
            return;
        }
        // clip the source to the framebuffer (the main menu copies 256x256 even when the window is smaller); the destination follows
        long sx0 = Math.max(0L, glSrcX);
        long sy0 = Math.max(0L, glSrcY);
        long sx1 = Math.min((long) glSrcX + w, t.width);
        long sy1 = Math.min((long) glSrcY + h, t.height);
        if (sx1 <= sx0 || sy1 <= sy0) {
            return;
        }
        int copyWidth = (int) (sx1 - sx0);
        int copyHeight = (int) (sy1 - sy0);
        int dx0 = dstX + (int) (sx0 - glSrcX);
        int dy0 = dstY + (int) (sy0 - glSrcY);

        ensureNoRenderPass();
        VkCommandBuffer cb = mainCommandBuffer;
        VkUtil.imageBarrier(cb, t.colorImage, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            // reversed Y range = vertical mirror: GL row sy0 (bottom of the rectangle) becomes the first destination row
            region.srcOffsets(0).set((int) sx0, t.height - (int) sy0, 0);
            region.srcOffsets(1).set((int) sx1, t.height - (int) sy1, 1);
            region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(dstMip).baseArrayLayer(0).layerCount(1);
            region.dstOffsets(0).set(dx0, dy0, 0);
            region.dstOffsets(1).set(dx0 + copyWidth, dy0 + copyHeight, 1);
            vkCmdBlitImage(cb, t.colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dstImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region,
                    VK_FILTER_NEAREST);
        }
        VkUtil.imageBarrier(cb, t.colorImage, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
    }

    // ---------------------------------------------------------------------------------------------------------
    // render passes
    // ---------------------------------------------------------------------------------------------------------

    private static void chooseFormats() {
        colorFormat = DeviceSetup.chooseColorFormat(physicalDevice);
        depthFormat = DeviceSetup.chooseDepthFormat(physicalDevice);
        if (colorFormat == 0 || depthFormat == 0) {
            throw new IllegalStateException("No usable offscreen formats (color " + colorFormat + ", depth " + depthFormat + ")");
        }
        linearBlit = DeviceSetup.optimalFeatures(physicalDevice, colorFormat, VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
    }

    private static void createRenderPasses() {
        // the LOAD pass resumes after every interruption; the CLEAR pass starts a frame (or the first full glClear) without loading
        renderPassLoad = createRenderPass(VK_ATTACHMENT_LOAD_OP_LOAD, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        // CLEAR: previous contents are discarded, so UNDEFINED is a valid initial layout (also initialises a brand new target)
        renderPassClear = createRenderPass(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_UNDEFINED);
        VkUtil.setObjectName(device, VK_OBJECT_TYPE_RENDER_PASS, renderPassLoad, "Offscreen pass (load)");
        VkUtil.setObjectName(device, VK_OBJECT_TYPE_RENDER_PASS, renderPassClear, "Offscreen pass (clear)");
    }

    private static long createRenderPass(int loadOp, int colorInitialLayout, int depthInitialLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(2, stack);
            attachments.get(0)
                    .format(colorFormat)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(loadOp)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(colorInitialLayout)
                    .finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            attachments.get(1)
                    .format(depthFormat)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(loadOp)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(depthInitialLayout)
                    .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkAttachmentReference.Buffer colorReference = VkAttachmentReference.calloc(1, stack)
                    .attachment(0)
                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkAttachmentReference depthReference = VkAttachmentReference.calloc(stack)
                    .attachment(1)
                    .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorReference)
                    .pDepthStencilAttachment(depthReference);

            int attachmentStages = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
                    | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            int attachmentWrites = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            VkSubpassDependency.Buffer dependencies = VkSubpassDependency.calloc(2, stack);
            // previous pass / clear: its attachment writes must be visible to this pass' loads, blending and depth tests
            dependencies.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(attachmentStages)
                    .dstStageMask(attachmentStages)
                    .srcAccessMask(attachmentWrites)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                            | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            // blit / readback / capture after the pass
            dependencies.get(1)
                    .srcSubpass(0)
                    .dstSubpass(VK_SUBPASS_EXTERNAL)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .srcAccessMask(attachmentWrites)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);

            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachments)
                    .pSubpasses(subpass)
                    .pDependencies(dependencies);
            LongBuffer pRenderPass = stack.mallocLong(1);
            VkUtil.check(vkCreateRenderPass(device, info, null, pRenderPass), "vkCreateRenderPass");
            return pRenderPass.get(0);
        }
    }

    private static void beginPass(long renderPass, boolean clearValues, float r, float g, float b, float a, float depth) {
        beginPass(mainCommandBuffer, activeTarget(), renderPass, clearValues, r, g, b, a, depth);
        inRenderPass = true;
    }

    private static void beginPass(VkCommandBuffer cb, OffscreenTarget t, long renderPass, boolean clearValues, float r, float g, float b,
                                  float a, float depth) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRenderPassBeginInfo info = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass)
                    .framebuffer(t.framebuffer);
            info.renderArea().offset().set(0, 0);
            info.renderArea().extent().set(t.width, t.height);
            if (clearValues) {
                VkClearValue.Buffer values = VkClearValue.calloc(2, stack);
                values.position(0);
                values.color().float32(0, r).float32(1, g).float32(2, b).float32(3, a);
                values.position(1);
                values.depthStencil().depth(depth).stencil(0);
                values.position(0);
                info.pClearValues(values);
            }
            vkCmdBeginRenderPass(cb, info, VK_SUBPASS_CONTENTS_INLINE);
        }
    }

    private static void endPass() {
        if (inRenderPass) {
            vkCmdEndRenderPass(mainCommandBuffer);
            inRenderPass = false;
        }
    }

    /** A (re)created target is UNDEFINED: one empty CLEAR pass gives both images their attachment layout and black / depth 1. */
    private static void initializeTarget() {
        beginPass(mainCommandBuffer, target, renderPassClear, true, 0f, 0f, 0f, 1f, 1f);
        vkCmdEndRenderPass(mainCommandBuffer);
    }

    private static int clampTargetSize(int size) {
        return Math.max(1, Math.min(size, maxTargetDimension));
    }

    // ---------------------------------------------------------------------------------------------------------
    // frame slots, command buffers, synchronisation
    // ---------------------------------------------------------------------------------------------------------

    private static void createFrameResources() {
        slots = new FrameSlot[framesInFlight];
        int uniformAlignment = (int) Math.max(16, Math.min(deviceInfo.minUniformBufferOffsetAlignment(), 4096));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            // command buffers are re-recorded every frame and reset with their pool (vkResetCommandPool), no per-buffer reset flag
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                    .queueFamilyIndex(queueFamilyIndex);
            LongBuffer pHandle = stack.mallocLong(1);

            for (int i = 0; i < framesInFlight; i++) {
                FrameSlot slot = new FrameSlot();
                slots[i] = slot; // registered first so that a failure below is cleaned up by destroyAll
                VkUtil.check(vkCreateFence(device, fenceInfo, null, pHandle), "vkCreateFence");
                slot.fence = pHandle.get(0);
                VkUtil.check(vkCreateCommandPool(device, poolInfo, null, pHandle), "vkCreateCommandPool");
                slot.commandPool = pHandle.get(0);
                VkUtil.check(vkCreateCommandPool(device, poolInfo, null, pHandle), "vkCreateCommandPool (upload)");
                slot.uploadCommandPool = pHandle.get(0);
                VkUtil.check(vkCreateSemaphore(device, semaphoreInfo, null, pHandle), "vkCreateSemaphore (imageAvailable)");
                slot.imageAvailable = pHandle.get(0);

                slot.main = allocateCommandBuffer(stack, slot.commandPool, "main");
                slot.upload = allocateCommandBuffer(stack, slot.uploadCommandPool, "upload");
                VkUtil.setObjectName(device, VK_OBJECT_TYPE_COMMAND_BUFFER, slot.main.address(), "Main CB " + i);
                VkUtil.setObjectName(device, VK_OBJECT_TYPE_COMMAND_BUFFER, slot.upload.address(), "Upload CB " + i);

                slot.vertex = new StreamAllocator("vertex", allocator, VERTEX_CHUNK_BYTES,
                        VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, 16, true);
                // like the vertex stream: a caller may bind an index-stream slice as a vertex buffer (and the other way round)
                slot.index = new StreamAllocator("index", allocator, INDEX_CHUNK_BYTES,
                        VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, 16, true);
                // uniform chunk buffers are cached by the pipeline module (descriptor sets): keep their handles stable
                slot.uniform = new StreamAllocator("uniform", allocator, UNIFORM_CHUNK_BYTES, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                        uniformAlignment, false);
                slot.staging = new StreamAllocator("staging", allocator, STAGING_CHUNK_BYTES, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, 16, true);
            }

            // flushAndWait waits on its own fence so that the slot fences keep their "submitted once per frame" meaning
            fenceInfo.flags(0);
            VkUtil.check(vkCreateFence(device, fenceInfo, null, pHandle), "vkCreateFence (flush)");
            flushFence = pHandle.get(0);
        }
    }

    private static VkCommandBuffer allocateCommandBuffer(MemoryStack stack, long pool, String what) {
        VkCommandBufferAllocateInfo info = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(pool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1);
        PointerBuffer pBuffer = stack.mallocPointer(1);
        VkUtil.check(vkAllocateCommandBuffers(device, info, pBuffer), "vkAllocateCommandBuffers (" + what + ")");
        return new VkCommandBuffer(pBuffer.get(0), device);
    }

    private static void selectSlot(int index) {
        currentSlot = index;
        FrameSlot slot = slots[index];
        mainCommandBuffer = slot.main;
        uploadCommandBuffer = slot.upload;
        vertexStream = slot.vertex;
        indexStream = slot.index;
        uniformStream = slot.uniform;
        stagingStream = slot.staging;
    }

    private static void beginCommandBuffers(FrameSlot slot) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo info = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            VkUtil.check(vkBeginCommandBuffer(slot.upload, info), "vkBeginCommandBuffer (upload)");
            VkUtil.check(vkBeginCommandBuffer(slot.main, info), "vkBeginCommandBuffer (main)");
        }
        inRenderPass = false;
    }

    /** Upload CB epilogue: makes the transfers of this frame visible to the draws / shader reads of the main CB, then ends it. */
    private static void endUploadCommandBuffer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT | VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT
                            | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(uploadCommandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                            | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, barrier, null, null);
        }
        VkUtil.check(vkEndCommandBuffer(uploadCommandBuffer), "vkEndCommandBuffer (upload)");
    }

    private static void flushStreams(FrameSlot slot) {
        slot.vertex.flushHostWrites();
        slot.index.flushHostWrites();
        slot.uniform.flushHostWrites();
        slot.staging.flushHostWrites();
    }

    private static void submit(FrameSlot slot, int imageIndex) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(uploadCommandBuffer, mainCommandBuffer));
            if (imageIndex >= 0 && presenter == null) {
                // waitSemaphoreCount is shared by two arrays: LWJGL does not derive it from either buffer
                submitInfo.waitSemaphoreCount(1)
                        .pWaitSemaphores(stack.longs(slot.imageAvailable))
                        .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
                        .pSignalSemaphores(stack.longs(swapchain.renderFinished[imageIndex]));
            }
            // the fence is reset only now that a submit that signals it is certain
            VkUtil.check(vkResetFences(device, slot.fence), "vkResetFences");
            VkUtil.check(vkQueueSubmit(queue, submitInfo, slot.fence), "vkQueueSubmit");
            if (imageIndex >= 0 && presenter != null) {
                presenter.submitted(imageIndex, slot.fence);
            }
        }
    }

    /** Advances to the next frame slot and prepares it: fence wait, deferred deletions, stream reset, new command buffers. */
    private static void nextFrame() {
        frameNumber++;
        if (activeTarget != null) {
            VkLog.LOGGER.warn("A frame ended while an extra render target was still selected: back to MAIN");
            activeTarget = null;
            CommandStream.onTargetChanged();
        }
        selectSlot((int) (frameNumber % framesInFlight));
        FrameSlot slot = slots[currentSlot];

        VkUtil.waitFence(device, slot.fence, "frame slot " + currentSlot);
        if (presenter != null) {
            presenter.publishCompleted(); // consumes this slot's fence before it is reset
        }
        if (capture != null) {
            capture.collect(currentSlot);
        }
        runDeletions(slot.deletions);
        slot.vertex.reset();
        slot.index.reset();
        slot.uniform.reset();
        slot.staging.reset();
        VkUtil.check(vkResetCommandPool(device, slot.commandPool, 0), "vkResetCommandPool");
        VkUtil.check(vkResetCommandPool(device, slot.uploadCommandPool, 0), "vkResetCommandPool (upload)");
        beginCommandBuffers(slot);

        applyPendingResize();

        fireFrameBegin();
        fireCommandBufferReset();
    }

    /**
     * Recreates the offscreen target when the window size (clamped) differs from it; runs with the next frame's command buffers begun.
     * The new target is an independent image / framebuffer, so nothing is waited for: the frames still in flight keep reading the old
     * one, which is released through the deletion queue of the slot that now starts (it runs once that slot's frame has completed).
     */
    private static void applyPendingResize() {
        if (windowWidth <= 0 || windowHeight <= 0) {
            return; // minimised: keep the target, nothing is presented
        }
        int width = clampTargetSize(windowWidth);
        int height = clampTargetSize(windowHeight);
        OffscreenTarget old = target;
        if (width == old.width && height == old.height) {
            return;
        }
        target = OffscreenTarget.create(width, height, colorFormat, depthFormat, renderPassLoad);
        deferDestroy(old::destroy);
        initializeTarget();
        VkLog.LOGGER.info("Offscreen target resized to {}x{}", width, height);
        fireResize(width, height);
    }

    private static void runDeletions(ArrayList<Runnable> queue) {
        // index based: a deferred action may defer further destruction into the same queue
        for (int i = 0; i < queue.size(); i++) {
            try {
                queue.get(i).run();
            } catch (Throwable t) {
                VkLog.LOGGER.error("Deferred Vulkan resource destruction failed", t);
            }
        }
        queue.clear();
    }

    // ---------------------------------------------------------------------------------------------------------
    // swapchain / present
    // ---------------------------------------------------------------------------------------------------------

    /** Returns the acquired swapchain image or -1 when this frame is not presented (minimised / occluded / not ready). */
    private static int acquireImage(FrameSlot slot) {
        if (windowWidth <= 0 || windowHeight <= 0) {
            return -1;
        }
        if (usePresenter() || presenter != null) {
            if (!usePresenter()) {
                switchToSwapchain();
            } else {
                if (presenter == null || swapchainDirty || presenter.width != windowWidth || presenter.height != windowHeight) {
                    if (!ensurePresenter()) {
                        return acquireImage(slot); // presenter failed: swapchain from now on
                    }
                }
                return presenter.acquire();
            }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            if (swapchain == null || swapchainDirty) {
                recreateSwapchain();
                if (swapchain == null) {
                    return -1;
                }
            }
            int result = swapchain.acquire(slot.imageAvailable, ACQUIRE_TIMEOUT_NANOS);
            if (result == VK_SUCCESS) {
                return swapchain.lastImageIndex;
            }
            if (result == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                noteSuboptimal();
                return swapchain.lastImageIndex; // the image is usable and its semaphore is signalled
            }
            if (result == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                swapchainDirty = true;
                continue;
            }
            if (result == VK_TIMEOUT || result == VK_NOT_READY) {
                return -1;
            }
            VkUtil.check(result, "vkAcquireNextImageKHR");
            return -1;
        }
        return -1;
    }

    /** Presenter off for good after a failure (logged once), the swapchain is used from then on. */
    private static boolean presenterFailed;

    /** Surfaces of the layer presenter: the frames in flight + the shown one + one the compositor may still read, at least the configured count. */
    private static int presenterSurfaces() {
        return Math.max(VulkanConfig.presenterSurfaces(), framesInFlight + 2);
    }

    /** macOS, vsync off, fml.vulkan.macPresenter=auto: present through the IOSurface layer presenter (no drawable wait). */
    private static boolean usePresenter() {
        return MAC && !presenterFailed && !effectiveVsync() && "auto".equals(VulkanConfig.macPresenter());
    }

    /** Creates or resizes the layer presenter (and drops the swapchain); false when it is not available. */
    private static boolean ensurePresenter() {
        VkUtil.check(vkDeviceWaitIdle(device), "vkDeviceWaitIdle (presenter)");
        if (presenter != null) {
            presenter.publishCompleted();
        }
        if (swapchain != null) {
            swapchain.destroy();
            swapchain = null;
        }
        swapchainDirty = false;
        try {
            if (presenter == null) {
                presenter = LayerPresenter.create(device, glfwWindowHandle, windowWidth, windowHeight, presenterSurfaces());
            } else {
                presenter.resize(windowWidth, windowHeight);
            }
        } catch (RuntimeException e) {
            VkLog.LOGGER.warn("Layer presenter failed, falling back to the swapchain", e);
            if (presenter != null) {
                LayerPresenter p = presenter;
                presenter = null;
                guard("layer presenter", p::destroy);
            }
        }
        if (presenter == null) {
            presenterFailed = true;
            swapchainDirty = true;
            return false;
        }
        return true;
    }

    /** vsync was turned on (or the presenter disabled): back to the swapchain, which recreates itself at the next acquire. */
    private static void switchToSwapchain() {
        VkUtil.check(vkDeviceWaitIdle(device), "vkDeviceWaitIdle (presenter)");
        LayerPresenter p = presenter;
        presenter = null;
        if (p != null) {
            p.publishCompleted();
            p.destroy();
        }
        swapchainDirty = true;
    }

    private static void presentImage(FrameSlot slot, int imageIndex) {
        if (presenter != null) {
            return; // published by LayerPresenter.publishCompleted once the frame's fence has signalled
        }
        int result = swapchain.present(queue, imageIndex);
        if (result >= 0) {
            notePresent(System.nanoTime());
        }
        if (result == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
            noteSuboptimal();
        } else if (result == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
            swapchainDirty = true;
        } else {
            VkUtil.check(result, "vkQueuePresentKHR");
        }
    }

    /** SUBOPTIMAL: recreate only if the surface really changed size (some platforms report it permanently). */
    private static void noteSuboptimal() {
        int[] extent = swapchain.currentSurfaceExtent(surface);
        if (extent == null || extent[0] != swapchain.width || extent[1] != swapchain.height) {
            swapchainDirty = true;
        }
    }

    private static void recreateSwapchain() {
        VkUtil.check(vkDeviceWaitIdle(device), "vkDeviceWaitIdle (swapchain)");
        Swapchain old = swapchain;
        Swapchain fresh = Swapchain.create(surface, windowWidth, windowHeight, effectiveVsync(), old);
        if (old != null) {
            old.destroy();
        }
        swapchain = fresh;
        swapchainDirty = false;
    }

    /**
     * Tail of the main command buffer: offscreen colour -> capture buffer and/or swapchain image (blit, scaled when the sizes differ),
     * then everything back to the layouts the next frame expects.
     */
    private static void recordPresentCommands(int imageIndex, boolean capturing, long frame) {
        boolean presenting = imageIndex >= 0;
        if (!presenting && !capturing) {
            return;
        }
        OffscreenTarget t = target;
        VkCommandBuffer cb = mainCommandBuffer;
        VkUtil.beginLabel(cb, "Present");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LayerPresenter lp = presenter;
            long swapImage = !presenting ? 0 : lp != null ? lp.images[imageIndex] : swapchain.images[imageIndex];
            int dstWidth = !presenting ? 0 : lp != null ? lp.width : swapchain.width;
            int dstHeight = !presenting ? 0 : lp != null ? lp.height : swapchain.height;

            // offscreen colour: attachment -> transfer source; swapchain image: undefined -> transfer destination (the semaphore wait
            // is at the TRANSFER stage, so the barrier's source stage chains with it)
            VkImageMemoryBarrier.Buffer pre = VkImageMemoryBarrier.calloc(presenting ? 2 : 1, stack);
            VkUtil.fillImageBarrier(pre.position(0), t.colorImage, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            if (presenting) {
                VkUtil.fillImageBarrier(pre.position(1), swapImage, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT);
            }
            pre.position(0);
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | (presenting ? VK_PIPELINE_STAGE_TRANSFER_BIT : 0),
                    VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, pre);

            if (capturing) {
                capture.record(cb, t.colorImage, t.width, t.height, currentSlot, frame);
            }

            if (presenting) {
                boolean sameSize = dstWidth == t.width && dstHeight == t.height;
                VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
                region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.srcOffsets(0).set(0, 0, 0);
                region.srcOffsets(1).set(t.width, t.height, 1);
                region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.dstOffsets(0).set(0, 0, 0);
                region.dstOffsets(1).set(dstWidth, dstHeight, 1);
                vkCmdBlitImage(cb, t.colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, swapImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        region, sameSize || !linearBlit ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
            }

            VkImageMemoryBarrier.Buffer post = VkImageMemoryBarrier.calloc(presenting ? 2 : 1, stack);
            VkUtil.fillImageBarrier(post.position(0), t.colorImage, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, 0, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            if (presenting) {
                // an IOSurface image is read by the window server after the fence: GENERAL (no Vulkan consumer); a swapchain image is presented
                VkUtil.fillImageBarrier(post.position(1), swapImage, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        lp != null ? VK_IMAGE_LAYOUT_GENERAL : KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0);
            }
            post.position(0);
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | (presenting ? VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT : 0),
                    0, null, null, post);
        }
        VkUtil.endLabel(cb);
    }

    // ---------------------------------------------------------------------------------------------------------
    // readback buffer
    // ---------------------------------------------------------------------------------------------------------

    private static void ensureReadbackBuffer(long bytes) {
        if (readbackBuffer != 0 && readbackCapacity >= bytes) {
            return;
        }
        destroyReadbackBuffer(); // readColor waits for the GPU, the old buffer is idle
        long capacity = VkUtil.alignUp(bytes, READBACK_GRANULARITY);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(capacity)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            // random access = host-cached memory: the CPU reads every byte (write-combined stream memory would be very slow to read)
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
            VkUtil.check(Vma.vmaCreateBuffer(allocator, bufferInfo, allocationInfo, pBuffer, pAllocation, info), "vmaCreateBuffer (readback)");
            readbackBuffer = pBuffer.get(0);
            readbackAllocation = pAllocation.get(0);
            readbackAddress = info.pMappedData();
            readbackCapacity = capacity;
            IntBuffer pFlags = stack.mallocInt(1);
            Vma.vmaGetAllocationMemoryProperties(allocator, readbackAllocation, pFlags);
            readbackCoherent = (pFlags.get(0) & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
        }
    }

    private static void destroyReadbackBuffer() {
        if (readbackBuffer != 0) {
            Vma.vmaDestroyBuffer(allocator, readbackBuffer, readbackAllocation);
            readbackBuffer = 0;
            readbackAllocation = 0;
            readbackAddress = 0;
            readbackCapacity = 0;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // listeners
    // ---------------------------------------------------------------------------------------------------------

    private static void fireFrameBegin() {
        FrameListener[] l = listeners;
        for (int i = 0; i < l.length; i++) {
            l[i].onFrameBegin();
        }
    }

    private static void fireCommandBufferReset() {
        FrameListener[] l = listeners;
        for (int i = 0; i < l.length; i++) {
            l[i].onCommandBufferReset();
        }
    }

    private static void fireBeforePassEnd() {
        FrameListener[] l = listeners;
        for (int i = 0; i < l.length; i++) {
            l[i].beforePassEnd();
        }
    }

    private static void fireBeforeFrameEnd() {
        FrameListener[] l = listeners;
        for (int i = 0; i < l.length; i++) {
            l[i].beforeFrameEnd();
        }
    }

    private static void fireBeforePresent() {
        FrameListener[] l = listeners;
        for (int i = 0; i < l.length; i++) {
            l[i].beforePresent();
        }
    }

    private static void fireResize(int w, int h) {
        FrameListener[] l = listeners;
        for (int i = 0; i < l.length; i++) {
            l[i].onResize(w, h);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // debug statistics
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Debug mode: per frame (present to present) time, the CPU part of it (everything except the fence wait / acquire of the next
     * frame slot: game update + render recording + submit) and the draw statistics of the frame; one "Vulkan perf" line every
     * {@link #PERF_INTERVAL_FRAMES} frames with average / percentiles / worst frame, the number of frames above 50 ms, the draw
     * counts, the Java heap and the VMA allocations. Run with vsync off (-Pvk.vsync=false -Pvk.presentMode=immediate and
     * fpsLimit 0) to measure the render cost instead of the display refresh.
     */
    private static void perfSample(long frame) {
        long now = System.nanoTime();
        if (perfLastPresent != 0L) {
            float ms = (now - perfLastPresent) / 1.0e6f;
            perfFrameMs[perfCount++] = ms;
            if (ms > 50f) {
                perfStallFrames++;
            }
            if (perfFrameBegin != 0L) {
                perfCpuMs += (now - perfFrameBegin) / 1.0e6;
            }
            CommandStream.Stats st = CommandStream.currentStats();
            perfDraws += st.drawCalls();
            perfIndexed += st.indexedDrawCalls();
            perfPipelineBinds += st.pipelineBinds();
            perfDescriptorBinds += st.descriptorBinds();
            perfPushUpdates += st.pushConstantUpdates();
            perfMaxDraws = Math.max(perfMaxDraws, st.drawCalls());
        }
        perfLastPresent = now;
        if (perfCount < PERF_INTERVAL_FRAMES) {
            return;
        }
        float[] sorted = perfFrameMs.clone();
        Arrays.sort(sorted);
        double sum = 0.0;
        for (float v : sorted) {
            sum += v;
        }
        int n = perfCount;
        double avg = sum / n;
        Runtime rt = Runtime.getRuntime();
        long vmaBytes = vmaAllocatedBytes();
        VkLog.LOGGER.info("Vulkan perf @frame {}: {} frames, avg {} ms ({} fps) [cpu {} ms, slot wait {} ms], p50 {} p95 {} p99 {} max {} ms, "
                        + ">50 ms: {}, draws/frame avg {} max {} (indexed {}), pipeline binds {}, descriptor binds {}, push {}, heap {}/{} MB, "
                        + "vma {} MB, static buffers {}",
                frame, n, f1(avg), (int) (1000.0 / Math.max(avg, 0.001)), f1(perfCpuMs / n), f1(perfWaitMs / n), f1(sorted[n / 2]),
                f1(sorted[(int) (n * 0.95)]), f1(sorted[(int) (n * 0.99)]), f1(sorted[n - 1]), perfStallFrames, perfDraws / n, perfMaxDraws,
                perfIndexed / n, perfPipelineBinds / n, perfDescriptorBinds / n, perfPushUpdates / n,
                (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20, vmaBytes >> 20, staticBuffers.size());
        VkLog.LOGGER.info("Vulkan perf @frame {}: java heap after the last GC {}", frame, heapAfterLastGc());
        perfCount = 0;
        perfCpuMs = 0.0;
        perfWaitMs = 0.0;
        perfDraws = 0;
        perfIndexed = 0;
        perfPipelineBinds = 0;
        perfDescriptorBinds = 0;
        perfPushUpdates = 0;
        perfMaxDraws = 0;
        perfStallFrames = 0;
    }

    /** The heap occupancy right after the most recent GC cycle of each collector (the used heap itself includes garbage). */
    private static String heapAfterLastGc() {
        StringBuilder sb = new StringBuilder();
        try {
            for (java.lang.management.GarbageCollectorMXBean gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                if (!(gc instanceof com.sun.management.GarbageCollectorMXBean sun) || sun.getLastGcInfo() == null) {
                    continue;
                }
                long used = 0L;
                for (java.lang.management.MemoryUsage u : sun.getLastGcInfo().getMemoryUsageAfterGc().values()) {
                    used += u.getUsed();
                }
                sb.append(gc.getName()).append(": ").append(used >> 20).append(" MB (").append(gc.getCollectionCount()).append(" collections), ");
            }
        } catch (Throwable t) {
            return "unavailable";
        }
        return sb.length() == 0 ? "no GC yet" : sb.substring(0, sb.length() - 2);
    }

    private static String f1(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** Test / perf harness (render thread): bytes currently allocated through VMA over every memory heap, -1 when unavailable. */
    public static long debugVmaBytes() {
        return initialized ? vmaAllocatedBytes() : -1L;
    }

    /** Test / perf harness (render thread): number of live GPU resident static buffers (display list geometry, chunk meshes). */
    public static int debugStaticBufferCount() {
        return staticBuffers.size();
    }

    /** Bytes currently allocated through VMA over every memory heap (leak detection for the debug perf line). */
    private static long vmaAllocatedBytes() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties props = org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, props);
            int heaps = props.memoryHeapCount();
            org.lwjgl.util.vma.VmaBudget.Buffer budgets = org.lwjgl.util.vma.VmaBudget.calloc(heaps, stack);
            Vma.vmaGetHeapBudgets(allocator, budgets);
            long total = 0L;
            for (int i = 0; i < heaps; i++) {
                total += budgets.get(i).statistics().allocationBytes();
            }
            return total;
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static void logStats(FrameSlot slot, long frame) {
        int deferred = 0;
        for (FrameSlot s : slots) {
            deferred += s.deletions.size();
        }
        VkLog.LOGGER.info("Vulkan stats @frame {} (slot {}): vertex {} KiB/{} chunk(s), index {} KiB/{}, uniform {} KiB/{}, staging {} KiB/{}, "
                        + "{} static buffer(s), {} deferred destruction(s), target {}x{}",
                frame, currentSlot,
                slot.vertex.usedBytes() >> 10, slot.vertex.chunkCount(), slot.index.usedBytes() >> 10, slot.index.chunkCount(),
                slot.uniform.usedBytes() >> 10, slot.uniform.chunkCount(), slot.staging.usedBytes() >> 10, slot.staging.chunkCount(),
                staticBuffers.size(), deferred, target.width, target.height);
    }

    // ---------------------------------------------------------------------------------------------------------
    // teardown
    // ---------------------------------------------------------------------------------------------------------

    /** Releases everything that exists; safe after a partial init, null / zero safe, and leaves the class ready for another init. */
    private static void destroyAll() {
        initialized = false;
        VkDevice dev = device;
        if (dev != null) {
            try {
                int result = vkDeviceWaitIdle(dev);
                if (result != VK_SUCCESS) {
                    VkLog.LOGGER.warn("vkDeviceWaitIdle failed during shutdown: {}", VkUtil.resultName(result));
                }
            } catch (Throwable t) {
                VkLog.LOGGER.warn("vkDeviceWaitIdle failed during shutdown", t);
            }

            guard("frame capture", () -> {
                if (capture != null) {
                    capture.shutdown();
                }
            });
            for (int round = 0; round < 4 && slots != null; round++) { // deferred actions may defer more
                boolean any = false;
                for (FrameSlot slot : slots) {
                    if (slot != null && !slot.deletions.isEmpty()) {
                        any = true;
                        runDeletions(slot.deletions);
                    }
                }
                if (!any) {
                    break;
                }
            }
            guard("static buffers", () -> {
                for (StaticRelease release : new ArrayList<>(staticBuffers)) {
                    release.destroyNow();
                }
            });
            staticBuffers.clear();
            guard("readback buffer", RenderCore::destroyReadbackBuffer);

            if (slots != null) {
                for (FrameSlot slot : slots) {
                    if (slot == null) {
                        continue;
                    }
                    guard("stream allocators", () -> {
                        if (slot.vertex != null) slot.vertex.shutdown();
                        if (slot.index != null) slot.index.shutdown();
                        if (slot.uniform != null) slot.uniform.shutdown();
                        if (slot.staging != null) slot.staging.shutdown();
                    });
                    guard("frame slot", () -> {
                        if (slot.imageAvailable != 0) vkDestroySemaphore(dev, slot.imageAvailable, null);
                        if (slot.fence != 0) vkDestroyFence(dev, slot.fence, null);
                        if (slot.uploadCommandPool != 0) vkDestroyCommandPool(dev, slot.uploadCommandPool, null);
                        if (slot.commandPool != 0) vkDestroyCommandPool(dev, slot.commandPool, null);
                    });
                }
            }
            if (flushFence != 0) {
                guard("flush fence", () -> vkDestroyFence(dev, flushFence, null));
            }
            if (swapchain != null) {
                guard("swapchain", swapchain::destroy);
            }
            if (presenter != null) {
                guard("layer presenter", presenter::destroy);
            }
            if (target != null) {
                guard("offscreen target", target::destroy);
            }
            if (renderPassClear != 0) {
                guard("render pass", () -> vkDestroyRenderPass(dev, renderPassClear, null));
            }
            if (renderPassLoad != 0) {
                guard("render pass", () -> vkDestroyRenderPass(dev, renderPassLoad, null));
            }
        }
        DeviceSetup.destroy(ctx); // VMA, device, surface, debug messenger, instance

        ctx = null;
        instance = null;
        physicalDevice = null;
        device = null;
        queue = null;
        queueFamilyIndex = 0;
        allocator = 0;
        surface = 0;
        deviceInfo = null;
        uma = false;
        colorFormat = 0;
        depthFormat = 0;
        renderPassLoad = 0;
        renderPassClear = 0;
        target = null;
        swapchain = null;
        presenter = null;
        glfwWindowHandle = 0L;
        swapchainDirty = false;
        slots = null;
        currentSlot = 0;
        frameNumber = 0;
        inRenderPass = false;
        endingPass = false;
        mainCommandBuffer = null;
        uploadCommandBuffer = null;
        flushFence = 0;
        vertexStream = null;
        indexStream = null;
        uniformStream = null;
        stagingStream = null;
        capture = null;
        activeTarget = null;
        deviceCaps = DeviceCaps.NONE;
        lowLatency = false;
        listeners = new FrameListener[0];
    }

    private static void guard(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            VkLog.LOGGER.warn("Shutdown: releasing {} failed", what, t);
        }
    }
}
