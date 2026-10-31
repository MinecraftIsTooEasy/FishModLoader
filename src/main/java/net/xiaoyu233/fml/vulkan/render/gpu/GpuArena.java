package net.xiaoyu233.fml.vulkan.render.gpu;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.core.StreamSlice;
import net.xiaoyu233.fml.vulkan.core.VkUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Paged GPU buffer arena (ARCHITECTURE-NATIVE 2.6, 5.6): large {@code VkBuffer} pages, each sub-allocated by a TLSF allocator
 * ({@link Tlsf}) whose unit is {@code unitBytes} (one terrain quad = 112 bytes), so every allocation offset is a whole number of units.
 * <ul>
 *   <li><b>Deferred frees.</b> {@link #free} only records the block; it is returned to the allocator when every frame in flight that
 *       could still reference it has completed (frame {@code N} frees are retired at the start of frame {@code N + framesInFlight}).</li>
 *   <li><b>Upload.</b> Unified memory (Apple, integrated GPUs): pages are host-visible/coherent and {@link #upload} is a memcpy into the
 *       mapped page; otherwise pages are device-local and the data goes through a slice of the frame's staging stream and a
 *       {@code vkCmdCopyBuffer} on the upload command buffer (which executes before the frame's render pass).</li>
 *   <li><b>Pages</b> are created on demand and released after {@link #IDLE_FRAMES_BEFORE_RELEASE} frames without any live block.</li>
 * </ul>
 * Render thread only. {@link #allocate}/{@link #free}/{@link #upload} allocate nothing on the Java heap except the returned
 * {@link GpuAllocation}.
 */
public final class GpuArena {
    /** Frames an empty page is kept before its buffer is released. */
    public static final int IDLE_FRAMES_BEFORE_RELEASE = 120;

    private static final class Page {
        final long buffer;
        final long vmaAllocation;
        final long mapped;           // host address of the page when it is host visible and coherent (direct write), else 0
        final Tlsf tlsf;
        final long bytes;
        long lastUsedFrame;
        int live;                    // blocks handed out and not yet retired

        Page(long buffer, long vmaAllocation, long mapped, Tlsf tlsf, long bytes, long frame) {
            this.buffer = buffer;
            this.vmaAllocation = vmaAllocation;
            this.mapped = mapped;
            this.tlsf = tlsf;
            this.bytes = bytes;
            this.lastUsedFrame = frame;
        }
    }

    private final String name;
    private final int usage;
    private final long pageBytes;
    private final int unitBytes;
    private final int pageUnits;
    private final ArrayList<Page> pages = new ArrayList<>();
    private int lastPage;

    // frees waiting for their frames to retire: a FIFO ring (frame numbers are monotonic)
    private long[] pendingFrame = new long[256];
    private int[] pendingPage = new int[256];
    private int[] pendingBlock = new int[256];
    private int pendingHead;
    private int pendingCount;

    private long usedBytes;          // live blocks (blocks freed by the caller but not yet retired are not counted)
    private long pendingBytes;       // freed by the caller, waiting for the GPU
    private long reservedBytes;
    private int pageCountValue;
    private boolean destroyed;
    private boolean warnedOom;
    private boolean directWrite = true;   // false after a unified-memory page turned out unusable

    public GpuArena(String name, int usage, long pageBytes, int unitBytes) {
        if (unitBytes <= 0 || (unitBytes & 3) != 0) {
            throw new IllegalArgumentException("unitBytes must be a positive multiple of 4: " + unitBytes);
        }
        if (pageBytes < unitBytes || pageBytes / unitBytes > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("pageBytes " + pageBytes + " does not hold between 1 and 2^30 units of " + unitBytes);
        }
        this.name = name;
        this.usage = usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        this.pageUnits = (int) (pageBytes / unitBytes);
        this.pageBytes = (long) pageUnits * unitBytes;
        this.unitBytes = unitBytes;
        GpuCore.register(this);
    }

    public String name() {
        return name;
    }

    public int usage() {
        return usage;
    }

    public long pageBytes() {
        return pageBytes;
    }

    public int unitBytes() {
        return unitBytes;
    }

    /**
     * Reserves {@code bytes} (rounded up to whole units). Returns null when the request is larger than a page or the device is out of
     * memory; the caller retries next frame (frees of earlier frames retire in the meantime).
     */
    public GpuAllocation allocate(long bytes) {
        if (destroyed) {
            throw new IllegalStateException("GpuArena " + name + " is destroyed");
        }
        long unitsLong = Math.max(1L, (bytes + unitBytes - 1L) / unitBytes);
        if (unitsLong > pageUnits) {
            VkLog.LOGGER.error("GpuArena {}: a request of {} bytes does not fit a page of {} bytes", name, bytes, pageBytes);
            return null;
        }
        int units = (int) unitsLong;
        int count = pages.size();
        for (int i = 0; i < count; i++) {
            int index = (lastPage + i) % count;
            Page page = pages.get(index);
            if (page == null) {
                continue;
            }
            int block = page.tlsf.allocate(units);
            if (block >= 0) {
                lastPage = index;
                return handOut(page, index, block, units);
            }
        }
        int index = createPage();
        if (index < 0) {
            return null;
        }
        Page page = pages.get(index);
        int block = page.tlsf.allocate(units);
        lastPage = index;
        return handOut(page, index, block, units);
    }

    private GpuAllocation handOut(Page page, int index, int block, int units) {
        page.live++;
        page.lastUsedFrame = RenderCore.frameNumber();
        long size = (long) units * unitBytes;
        usedBytes += size;
        return new GpuAllocation(page.buffer, (long) page.tlsf.offset(block) * unitBytes, size, index, block);
    }

    /** Returns the block once every frame in flight that may reference it has retired. Freeing twice is reported and ignored. */
    public void free(GpuAllocation a) {
        if (a == null) {
            return;
        }
        if (a.block < 0 || a.page < 0 || a.page >= pages.size() || pages.get(a.page) == null || pages.get(a.page).buffer != a.buffer) {
            VkLog.LOGGER.error("GpuArena {}: free of a foreign allocation {}", name, a);
            return;
        }
        if (a.freed) {
            VkLog.LOGGER.error("GpuArena {}: double free of {}", name, a, new IllegalStateException("double free"));
            return;
        }
        a.freed = true;
        usedBytes -= a.size;
        pendingBytes += a.size;
        if (pendingCount == pendingFrame.length) {
            growPending();
        }
        int slot = (pendingHead + pendingCount) % pendingFrame.length;
        pendingFrame[slot] = RenderCore.frameNumber();
        pendingPage[slot] = a.page;
        pendingBlock[slot] = a.block;
        pendingCount++;
    }

    private void growPending() {
        int capacity = pendingFrame.length;
        long[] frame = new long[capacity * 2];
        int[] page = new int[capacity * 2];
        int[] block = new int[capacity * 2];
        for (int i = 0; i < pendingCount; i++) {
            int from = (pendingHead + i) % capacity;
            frame[i] = pendingFrame[from];
            page[i] = pendingPage[from];
            block[i] = pendingBlock[from];
        }
        pendingFrame = frame;
        pendingPage = page;
        pendingBlock = block;
        pendingHead = 0;
    }

    /**
     * Copies the remaining bytes of {@code src} (position..limit, position unchanged) to the start of {@code a}. Unified memory:
     * memcpy into the mapped page. Otherwise: a staging slice of this frame and a copy on the upload command buffer.
     */
    public void upload(GpuAllocation a, ByteBuffer src) {
        int bytes = src.remaining();
        if (bytes == 0) {
            return;
        }
        if (bytes > a.size) {
            throw new IllegalArgumentException("upload of " + bytes + " bytes into an allocation of " + a.size);
        }
        Page page = pages.get(a.page);
        if (page == null || page.buffer != a.buffer || a.freed) {
            throw new IllegalStateException("upload into a freed allocation " + a);
        }
        if (page.mapped != 0L) {
            long dst = page.mapped + a.offset;
            if (src.isDirect()) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(src), dst, bytes);
            } else {
                MemoryUtil.memByteBuffer(dst, bytes).put(src.duplicate());
            }
            return;
        }
        StreamSlice staging = RenderCore.streamStaging(bytes);
        if (src.isDirect()) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(src), staging.address(), bytes);
        } else {
            staging.mapped().put(src.duplicate());
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).srcOffset(staging.offset()).dstOffset(a.offset).size(bytes);
            vkCmdCopyBuffer(RenderCore.uploadCommandBuffer(), staging.buffer(), a.buffer, region);
        }
    }

    /** Live bytes (blocks freed by the caller but not yet retired are excluded). */
    public long bytesUsed() {
        return usedBytes;
    }

    /** Bytes freed by the caller that wait for the GPU before they can be reused. */
    public long bytesPendingFree() {
        return pendingBytes;
    }

    /** Total size of the pages. */
    public long bytesReserved() {
        return reservedBytes;
    }

    public int pageCount() {
        return pageCountValue;
    }

    /** One line per page: live blocks, used / free units, free block count and the largest free block (fragmentation). */
    public String fragmentationReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("arena ").append(name).append(": ").append(pageCountValue).append(" page(s), used ").append(usedBytes >> 10)
                .append(" KiB, pending free ").append(pendingBytes >> 10).append(" KiB, reserved ").append(reservedBytes >> 10).append(" KiB");
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            if (p == null) {
                continue;
            }
            Tlsf t = p.tlsf;
            sb.append("\n  page ").append(i).append(": ").append(p.live).append(" blocks, ").append(t.usedUnits()).append('/')
                    .append(t.totalUnits()).append(" units used, ").append(t.freeBlockCount()).append(" free blocks, largest free ")
                    .append(t.largestFree()).append(" units").append(p.mapped != 0L ? ", host mapped" : "");
        }
        return sb.toString();
    }

    /** Consistency check of every page allocator (null when sound); for tests. */
    public String validate() {
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            if (p != null) {
                String problem = p.tlsf.validate();
                if (problem != null) {
                    return "page " + i + ": " + problem;
                }
            }
        }
        return null;
    }

    /** Releases every page at once (the caller guarantees the GPU is done with them, or the destruction is deferred like any other). */
    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        GpuCore.unregister(this);
        for (int i = 0; i < pages.size(); i++) {
            releasePage(i);
        }
        pages.clear();
        pendingCount = 0;
        usedBytes = 0L;
        pendingBytes = 0L;
    }

    // ---------------------------------------------------------------------------------------------------------
    // called by GpuCore at the start of every frame
    // ---------------------------------------------------------------------------------------------------------

    void onFrameBegin(long frame, int framesInFlight) {
        while (pendingCount > 0 && pendingFrame[pendingHead] + framesInFlight <= frame) {
            int page = pendingPage[pendingHead];
            int block = pendingBlock[pendingHead];
            pendingHead = (pendingHead + 1) % pendingFrame.length;
            pendingCount--;
            Page p = pages.get(page);
            if (p != null) {
                long size = (long) p.tlsf.size(block) * unitBytes;
                pendingBytes -= size;
                p.tlsf.free(block);
                p.live--;
                p.lastUsedFrame = frame;
            }
        }
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            if (p != null && p.live == 0 && frame - p.lastUsedFrame >= IDLE_FRAMES_BEFORE_RELEASE) {
                VkLog.LOGGER.info("GpuArena {}: releasing idle page {} ({} MiB)", name, i, p.bytes >> 20);
                releasePage(i);
            }
        }
    }

    private void releasePage(int index) {
        Page p = pages.get(index);
        if (p == null) {
            return;
        }
        pages.set(index, null);
        reservedBytes -= p.bytes;
        pageCountValue--;
        long buffer = p.buffer;
        long allocation = p.vmaAllocation;
        if (RenderCore.isInitialized()) {
            RenderCore.deferDestroy(() -> Vma.vmaDestroyBuffer(RenderCore.allocator(), buffer, allocation));
        }
    }

    private int createPage() {
        Page page = null;
        if (RenderCore.isUnifiedMemory() && directWrite) {
            page = tryCreatePage(true);
            if (page == null) {
                directWrite = false;
                VkLog.LOGGER.warn("GpuArena {}: unified memory is not usable for a page, staging through device-local memory", name);
            }
        }
        if (page == null) {
            page = tryCreatePage(false);
        }
        if (page == null) {
            if (!warnedOom) {
                warnedOom = true;
                VkLog.LOGGER.error("GpuArena {}: out of device memory creating a page of {} MiB ({} MiB reserved)", name, pageBytes >> 20,
                        reservedBytes >> 20);
            }
            return -1;
        }
        warnedOom = false;
        int index = pages.indexOf(null);
        if (index < 0) {
            pages.add(page);
            index = pages.size() - 1;
        } else {
            pages.set(index, page);
        }
        reservedBytes += page.bytes;
        pageCountValue++;
        return index;
    }

    private Page tryCreatePage(boolean unified) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(pageBytes)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack);
            if (unified) {
                allocationInfo.usage(Vma.VMA_MEMORY_USAGE_AUTO)
                        .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT
                                | Vma.VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT)
                        .requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                        .preferredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            } else {
                allocationInfo.usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE).flags(Vma.VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT);
            }
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
            int result = Vma.vmaCreateBuffer(RenderCore.allocator(), bufferInfo, allocationInfo, pBuffer, pAllocation, info);
            if (result == VK_ERROR_OUT_OF_DEVICE_MEMORY || result == VK_ERROR_OUT_OF_HOST_MEMORY || result == VK_ERROR_FEATURE_NOT_PRESENT
                    || result == VK_ERROR_MEMORY_MAP_FAILED) {
                return null;
            }
            VkUtil.check(result, "vmaCreateBuffer (arena " + name + ")");
            long mapped = unified ? info.pMappedData() : 0L;
            if (unified && mapped == 0L) {
                Vma.vmaDestroyBuffer(RenderCore.allocator(), pBuffer.get(0), pAllocation.get(0));
                return null;
            }
            VkUtil.setObjectName(RenderCore.device(), VK_OBJECT_TYPE_BUFFER, pBuffer.get(0), "Arena " + name);
            return new Page(pBuffer.get(0), pAllocation.get(0), mapped, new Tlsf(pageUnits), pageBytes,
                    RenderCore.frameNumber());
        }
    }
}
