package net.xiaoyu233.fml.vulkan.render.gpu;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.core.VkUtil;
import net.xiaoyu233.fml.vulkan.render.state.Phase;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU time per frame phase (4.1, 5.6) from timestamp queries. One query pool per frame slot; {@link #beginPhase} writes a timestamp
 * that closes the previous phase and opens this one, {@link #endFrame} closes the last one. The results of a frame are read when its
 * slot comes round again (its fence has passed), so {@link #gpuMs} reports the latest completed frame, {@code framesInFlight} frames
 * behind the one being recorded. A phase entered several times in a frame accumulates. Without timestamp support (see
 * {@link DeviceFeatures#timestamps()}) every call is a no-op and every time is 0. Render thread only; allocation free once running.
 */
public final class GpuTimer {
    private static final int MAX_MARKERS = 62;
    private static final int POOL_QUERIES = 64;
    private static final int PHASES = Phase.values().length;
    private static final int NO_PHASE = -1;

    private static long[] pools;
    private static int[][] markerPhase;     // [slot][marker]
    private static int[] markerCount;       // per slot: markers written in the frame most recently recorded into the slot
    private static boolean[] submittedMarkers;
    private static long[] frameStamp;       // frame number the slot's pool was last reset for
    private static long results;            // native memory for vkGetQueryPoolResults: POOL_QUERIES * (value, availability)
    private static final double[] PHASE_MS = new double[PHASES];
    private static double frameMs;
    private static boolean broken;
    private static boolean overflowLogged;

    private GpuTimer() {
    }

    /** Marks the start of {@code p}: the GPU time up to this point belongs to the previous phase. */
    public static void beginPhase(Phase p) {
        if (!ready()) {
            return;
        }
        int slot = RenderCore.frameSlot();
        ensureFrame(slot);
        write(slot, p.ordinal());
    }

    /** Closes the last phase of the frame. Called before the present commands are recorded (GpuCore does it). */
    public static void endFrame() {
        if (!ready()) {
            return;
        }
        int slot = RenderCore.frameSlot();
        ensureFrame(slot);
        write(slot, NO_PHASE);
    }

    /** Milliseconds the GPU spent in {@code p} in the latest completed frame. */
    public static double gpuMs(Phase p) {
        return PHASE_MS[p.ordinal()];
    }

    /** Milliseconds from the first to the last timestamp of the latest completed frame (everything recorded between them). */
    public static double gpuFrameMs() {
        return frameMs;
    }

    // ---------------------------------------------------------------------------------------------------------

    private static boolean ready() {
        if (broken || !RenderCore.isInitialized() || !DeviceFeatures.timestamps()) {
            return false;
        }
        if (pools == null) {
            create();
        }
        return !broken;
    }

    private static void create() {
        int slots = RenderCore.framesInFlight();
        VkDevice device = RenderCore.device();
        long[] created = new long[slots];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                    .queryType(VK_QUERY_TYPE_TIMESTAMP)
                    .queryCount(POOL_QUERIES);
            LongBuffer pPool = stack.mallocLong(1);
            for (int i = 0; i < slots; i++) {
                int r = vkCreateQueryPool(device, info, null, pPool);
                if (r < 0) {
                    VkLog.LOGGER.warn("GpuTimer: vkCreateQueryPool failed ({}), GPU timings are off", VkUtil.resultName(r));
                    for (int j = 0; j < i; j++) {
                        vkDestroyQueryPool(device, created[j], null);
                    }
                    broken = true;
                    return;
                }
                created[i] = pPool.get(0);
            }
        }
        pools = created;
        markerPhase = new int[slots][MAX_MARKERS + 2];
        markerCount = new int[slots];
        submittedMarkers = new boolean[slots];
        frameStamp = new long[slots];
        java.util.Arrays.fill(frameStamp, -1L);
        results = MemoryUtil.nmemAllocChecked((long) POOL_QUERIES * 16L);
        GpuCore.ensureHooks();
    }

    /** First timestamp use of the slot's frame: collect the previous frame of this slot, reset the pool, write the start marker. */
    private static void ensureFrame(int slot) {
        long frame = RenderCore.frameNumber();
        if (frameStamp[slot] == frame) {
            return;
        }
        if (submittedMarkers[slot]) {
            collect(slot);
        }
        frameStamp[slot] = frame;
        VkCommandBuffer cb = RenderCore.mainCommandBuffer();
        if (RenderCore.inRenderPass()) {
            RenderCore.ensureNoRenderPass();     // vkCmdResetQueryPool is not allowed inside a render pass
        }
        vkCmdResetQueryPool(cb, pools[slot], 0, POOL_QUERIES);
        markerCount[slot] = 0;
        submittedMarkers[slot] = true;
        write(slot, NO_PHASE);
    }

    private static void write(int slot, int phase) {
        int n = markerCount[slot];
        if (n >= MAX_MARKERS) {
            if (!overflowLogged) {
                overflowLogged = true;
                VkLog.LOGGER.warn("GpuTimer: more than {} phase changes in a frame, the rest is not timed", MAX_MARKERS);
            }
            return;
        }
        markerPhase[slot][n] = phase;
        // BOTTOM_OF_PIPE: the timestamp is taken when all previous commands finished
        vkCmdWriteTimestamp(RenderCore.mainCommandBuffer(), VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pools[slot], n);
        markerCount[slot] = n + 1;
    }

    private static void collect(int slot) {
        int n = markerCount[slot];
        submittedMarkers[slot] = false;
        if (n < 2) {
            return;
        }
        int r = nvkGetQueryPoolResults(RenderCore.device(), pools[slot], 0, n, (long) n * 16L, results, 16L,
                VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
        if (r < 0 || r == VK_NOT_READY) {
            return;     // keep the previous numbers
        }
        for (int i = 0; i < n; i++) {
            if (MemoryUtil.memGetLong(results + (long) i * 16L + 8L) == 0L) {
                return; // a timestamp is not available (the frame was not submitted): keep the previous numbers
            }
        }
        int bits = RenderCore.deviceCaps().timestampValidBits();
        long mask = bits >= 64 ? -1L : (1L << bits) - 1L;
        double nsPerTick = RenderCore.deviceCaps().timestampPeriodNs();
        for (int i = 0; i < PHASES; i++) {
            PHASE_MS[i] = 0.0;
        }
        long previous = MemoryUtil.memGetLong(results) & mask;
        long first = previous;
        for (int i = 1; i < n; i++) {
            long now = MemoryUtil.memGetLong(results + (long) i * 16L) & mask;
            long ticks = (now - previous) & mask;
            int phase = markerPhase[slot][i - 1];
            if (phase >= 0) {
                PHASE_MS[phase] += ticks * nsPerTick / 1.0e6;
            }
            previous = now;
        }
        frameMs = ((previous - first) & mask) * nsPerTick / 1.0e6;
    }

    // ---------------------------------------------------------------------------------------------------------
    // called by GpuCore
    // ---------------------------------------------------------------------------------------------------------

    /** Frame begin: the slot's previous frame is complete, read it and arm the new one. */
    static void onFrameBegin() {
        if (ready()) {
            ensureFrame(RenderCore.frameSlot());
        }
    }

    static void onCommandBufferReset() {
        // flushAndWait mid frame: the timestamps written so far were submitted and executed; the pool keeps its queries and the
        // markers continue in the new command buffer, nothing to do
    }

    static void shutdown() {
        if (pools != null) {
            VkDevice device = RenderCore.device();
            if (device != null) {
                for (long pool : pools) {
                    vkDestroyQueryPool(device, pool, null);
                }
            }
            pools = null;
            MemoryUtil.nmemFree(results);
            results = 0L;
        }
        markerPhase = null;
        markerCount = null;
        submittedMarkers = null;
        frameStamp = null;
        for (int i = 0; i < PHASES; i++) {
            PHASE_MS[i] = 0.0;
        }
        frameMs = 0.0;
        broken = false;
        overflowLogged = false;
    }
}
