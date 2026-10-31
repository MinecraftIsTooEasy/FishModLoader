package net.xiaoyu233.fml.vulkan.render.gpu;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.core.VkUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Asynchronous colour readback (1.13, 5.6). {@link #readColorAsync} records a copy of a rectangle of a {@link RenderTarget} into a
 * host-cached buffer in the current frame's command buffer and returns at once (no GPU wait); when the frame's fence has signalled,
 * at the start of the frame {@code framesInFlight} frames later at the latest, the callback receives the pixels on the render thread.
 *
 * <p>The delivered buffer is little-endian BGRA, tightly packed, rows bottom-up (GL order), alpha 255 (the framebuffer has no alpha),
 * exactly {@code w * h * 4} bytes with position 0 / limit {@code w * h * 4}; pixels of the rectangle outside the target are 0. It is
 * only valid during the callback: copy what must outlive it. The staging buffers and the conversion buffer are pooled, so steady-state
 * readbacks (a screenshot per second) allocate nothing on the Java heap beyond the request record of the pool's first uses.
 */
public final class Readback {
    private static final int GRANULARITY = 256 * 1024;

    private static final class Request {
        long buffer;
        long allocation;
        long address;
        long capacity;
        boolean coherent;
        long frame;               // frame in which the copy was recorded
        int copyW, copyH;         // clipped size actually copied (0 = nothing)
        int offX, offY;           // position of the copied part inside the requested rectangle (GL: x right, y up)
        int w, h;                 // requested size
        boolean swapRedBlue;
        Consumer<ByteBuffer> callback;
    }

    private static final ArrayList<Request> PENDING = new ArrayList<>();
    private static final ArrayList<Request> POOL = new ArrayList<>();
    private static ByteBuffer out;           // conversion buffer handed to callbacks
    private static boolean delivering;
    private static int completed;

    private Readback() {
    }

    /**
     * BGRA rows bottom-up (GL order); the callback runs on the render thread after the fence (see the class comment). Ends the running
     * render pass (pending merged draws are recorded first): call it at the point of the frame whose contents are wanted.
     */
    public static void readColorAsync(RenderTarget t, int glX, int glY, int w, int h, Consumer<ByteBuffer> onReady) {
        if (!RenderCore.isInitialized()) {
            throw new IllegalStateException("Readback before the Vulkan backend started");
        }
        if (w <= 0 || h <= 0 || onReady == null) {
            throw new IllegalArgumentException("readColorAsync needs a positive size and a callback");
        }
        if ((long) w * h * 4L > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("readback region too large: " + w + "x" + h);
        }
        GpuCore.ensureHooks();
        int tw = t.width();
        int th = t.height();
        long x0 = Math.max(0L, glX);
        long y0 = Math.max(0L, glY);
        long x1 = Math.min((long) glX + w, tw);
        long y1 = Math.min((long) glY + h, th);
        Request r = new Request();
        r.frame = RenderCore.frameNumber();
        r.w = w;
        r.h = h;
        r.callback = onReady;
        r.swapRedBlue = RenderCore.colorFormat() == VK_FORMAT_R8G8B8A8_UNORM;
        if (x1 > x0 && y1 > y0) {
            r.copyW = (int) (x1 - x0);
            r.copyH = (int) (y1 - y0);
            r.offX = (int) (x0 - glX);
            r.offY = (int) (y0 - glY);
            acquireBuffer(r, (long) r.copyW * r.copyH * 4L);
            record(t, r, (int) x0, (int) y1);
        }
        PENDING.add(r);
    }

    /** Readbacks whose callbacks have not run yet. */
    public static int pendingCount() {
        return PENDING.size();
    }

    /** Total callbacks delivered since start. */
    public static int completedCount() {
        return completed;
    }

    // ---------------------------------------------------------------------------------------------------------

    private static void record(RenderTarget t, Request r, int x0, int glTop) {
        RenderCore.ensureNoRenderPass();
        VkCommandBuffer cb = RenderCore.mainCommandBuffer();
        long image = t.colorImage();
        VkUtil.imageBarrier(cb, image, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            // GL row 0 is the bottom row, image row 0 the top row: the rectangle's top edge is GL row glTop
            region.imageOffset().set(x0, t.height() - glTop, 0);
            region.imageExtent().set(r.copyW, r.copyH, 1);
            vkCmdCopyImageToBuffer(cb, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, r.buffer, region);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(r.buffer)
                    .offset(0)
                    .size(VK_WHOLE_SIZE);
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
        }
        VkUtil.imageBarrier(cb, image, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        // the render pass of the target resumes (loadOp LOAD) with the next draw; cached binds survive a pass break, dynamic state is
        // per command buffer in Vulkan, so nothing needs to be invalidated
    }

    private static void acquireBuffer(Request r, long bytes) {
        for (int i = 0; i < POOL.size(); i++) {
            Request pooled = POOL.get(i);
            if (pooled.capacity >= bytes) {
                POOL.remove(i);
                r.buffer = pooled.buffer;
                r.allocation = pooled.allocation;
                r.address = pooled.address;
                r.capacity = pooled.capacity;
                r.coherent = pooled.coherent;
                return;
            }
        }
        long capacity = VkUtil.alignUp(bytes, GRANULARITY);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(capacity)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            // host-cached memory: the CPU reads every byte
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
            VkUtil.check(Vma.vmaCreateBuffer(RenderCore.allocator(), bufferInfo, allocationInfo, pBuffer, pAllocation, info),
                    "vmaCreateBuffer (readback)");
            r.buffer = pBuffer.get(0);
            r.allocation = pAllocation.get(0);
            r.address = info.pMappedData();
            r.capacity = capacity;
            IntBuffer pFlags = stack.mallocInt(1);
            Vma.vmaGetAllocationMemoryProperties(RenderCore.allocator(), r.allocation, pFlags);
            r.coherent = (pFlags.get(0) & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // called by GpuCore
    // ---------------------------------------------------------------------------------------------------------

    /** Frame {@code frame} begins: the copies recorded in frames up to {@code frame - framesInFlight} have completed. */
    static void onFrameBegin(long frame, int framesInFlight) {
        if (PENDING.isEmpty() || delivering) {
            return;
        }
        delivering = true;
        try {
            // oldest first; a callback may start another readback (appended to PENDING, recorded in the current frame)
            for (int i = 0; i < PENDING.size(); ) {
                Request r = PENDING.get(i);
                if (r.frame + framesInFlight > frame) {
                    i++;
                    continue;
                }
                PENDING.remove(i);
                deliver(r);
            }
        } finally {
            delivering = false;
        }
    }

    private static void deliver(Request r) {
        int bytes = r.w * r.h * 4;
        if (out == null || out.capacity() < bytes) {
            if (out != null) {
                MemoryUtil.memFree(out);
            }
            out = MemoryUtil.memAlloc(Math.max(bytes, 1 << 20)).order(ByteOrder.LITTLE_ENDIAN);
        }
        long dst = MemoryUtil.memAddress(out);
        if (r.copyW != r.w || r.copyH != r.h) {
            MemoryUtil.memSet(dst, 0, bytes);
        }
        if (r.copyW > 0) {
            if (!r.coherent) {
                Vma.vmaInvalidateAllocation(RenderCore.allocator(), r.allocation, 0, (long) r.copyW * r.copyH * 4L);
            }
            convert(r, dst);
        }
        out.clear().limit(bytes);
        try {
            r.callback.accept(out);
        } catch (Throwable e) {
            VkLog.LOGGER.error("Readback callback failed", e);
        }
        completed++;
        if (r.buffer != 0L) {
            POOL.add(pooledEntry(r));
            while (POOL.size() > 4) {
                Request evicted = POOL.remove(0);
                Vma.vmaDestroyBuffer(RenderCore.allocator(), evicted.buffer, evicted.allocation);
            }
        }
    }

    private static Request pooledEntry(Request r) {
        r.callback = null;
        return r;
    }

    /**
     * Source rows are top-down (image order) of the clipped part; the output has the requested size with rows bottom-up: the copied part
     * sits at ({@code offX}, {@code offY}) of the output (GL coordinates inside the rectangle).
     */
    private static void convert(Request r, long dst) {
        int copyW = r.copyW;
        int copyH = r.copyH;
        long src = r.address;
        boolean swap = r.swapRedBlue;
        for (int row = 0; row < copyH; row++) {
            // source row 0 is the top row of the copied part = GL row offY + copyH - 1 of the output
            long srcRow = src + (long) row * copyW * 4L;
            long dstRow = dst + ((long) (r.offY + copyH - 1 - row) * r.w + r.offX) * 4L;
            for (int x = 0; x < copyW; x++) {
                int px = MemoryUtil.memGetInt(srcRow + ((long) x << 2)) | 0xFF000000;   // little endian R,G,B,A (or B,G,R,A): alpha on top
                if (swap) {
                    px = (px & 0xFF00FF00) | ((px & 0xFF) << 16) | ((px >>> 16) & 0xFF);
                }
                MemoryUtil.memPutInt(dstRow + ((long) x << 2), px);
            }
        }
    }

    /** Releases pooled buffers and drops pending requests (their callbacks never run); the GPU is idle. */
    static void shutdown() {
        for (Request r : PENDING) {
            if (r.buffer != 0L && RenderCore.isInitialized()) {
                Vma.vmaDestroyBuffer(RenderCore.allocator(), r.buffer, r.allocation);
            }
        }
        PENDING.clear();
        for (Request r : POOL) {
            if (RenderCore.isInitialized()) {
                Vma.vmaDestroyBuffer(RenderCore.allocator(), r.buffer, r.allocation);
            }
        }
        POOL.clear();
        if (out != null) {
            MemoryUtil.memFree(out);
            out = null;
        }
    }
}
