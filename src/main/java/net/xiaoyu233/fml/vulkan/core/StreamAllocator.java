package net.xiaoyu233.fml.vulkan.core;

import net.xiaoyu233.fml.vulkan.VkLog;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Per-frame-slot linear allocator over persistently mapped host-visible buffers.
 * <p>
 * Requests are sub-allocated from fixed-size chunks (the VkBuffer handles of those chunks stay the same from frame to frame, the
 * pipeline module caches descriptor sets keyed by the uniform chunk buffer); {@link #reset()} rewinds every chunk (it is only called
 * once the GPU has finished the frame that used this allocator) instead of allocating new ones. A request bigger than a chunk gets
 * a dedicated buffer that is released at the next reset. Chunks that stayed unused for a long time are trimmed (when allowed).
 */
final class StreamAllocator {
    /** Resets (frames) an extra chunk may stay unused before it is freed again. */
    private static final int TRIM_AFTER_FRAMES = 300;

    private final String name;
    private final long vma;
    private final int chunkSize;
    private final int usage;
    private final int alignment;
    private final boolean trim;
    private final ArrayList<Chunk> chunks = new ArrayList<>();
    private final ArrayList<Chunk> dedicated = new ArrayList<>();
    private int cursor;
    private long resets;
    private long usedBytes;
    private boolean anyNonCoherent;

    /**
     * @param alignment byte alignment of every returned offset (power of two; 16 for vertex/index/staging data,
     *                  minUniformBufferOffsetAlignment for uniform data)
     * @param trim      free chunks that stayed unused for a long time; must be false when the buffer handles are cached outside
     */
    StreamAllocator(String name, long vma, int chunkSize, int usage, int alignment, boolean trim) {
        this.name = name;
        this.vma = vma;
        this.chunkSize = chunkSize;
        this.usage = usage;
        this.alignment = alignment <= 1 ? 1 : (Integer.bitCount(alignment) == 1 ? alignment : Integer.highestOneBit(alignment) << 1);
        this.trim = trim;
    }

    /** Returns {@code bytes} bytes (mapped position 0, limit = bytes); zero-length requests return a valid empty slice. */
    StreamSlice allocate(int bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException(name + " stream: negative allocation size " + bytes);
        }
        int size = Math.max(bytes, 1);
        if (size > chunkSize) {
            return allocateDedicated(size, bytes);
        }
        while (true) {
            if (cursor < chunks.size()) {
                Chunk chunk = chunks.get(cursor);
                int offset = VkUtil.alignUp(chunk.offset, alignment);
                if (offset + size <= chunk.size) {
                    chunk.offset = offset + size;
                    chunk.lastUsedReset = resets;
                    usedBytes += size;
                    return chunk.slice(offset, bytes);
                }
                cursor++; // full: continue with the next chunk (the tail stays unused for this frame)
                continue;
            }
            chunks.add(createChunk(chunkSize));
            cursor = chunks.size() - 1;
        }
    }

    private StreamSlice allocateDedicated(int size, int bytes) {
        Chunk chunk = createChunk(VkUtil.alignUp(size, alignment));
        chunk.offset = size;
        dedicated.add(chunk);
        usedBytes += size;
        return chunk.slice(0, bytes);
    }

    /** Rewinds the allocator. Only valid once the GPU is done with everything allocated from it. */
    void reset() {
        resets++;
        cursor = 0;
        usedBytes = 0;
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).offset = 0;
        }
        for (int i = 0; i < dedicated.size(); i++) {
            dedicated.get(i).destroy(vma);
        }
        dedicated.clear();
        if (trim) {
            while (chunks.size() > 1 && resets - chunks.get(chunks.size() - 1).lastUsedReset > TRIM_AFTER_FRAMES) {
                chunks.remove(chunks.size() - 1).destroy(vma);
            }
        }
    }

    /** Makes the CPU writes of this frame visible to the GPU when the memory is not host-coherent; called before queue submits. */
    void flushHostWrites() {
        if (!anyNonCoherent) {
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            if (!chunk.coherent && chunk.offset > 0) {
                Vma.vmaFlushAllocation(vma, chunk.allocation, 0, chunk.offset);
            }
        }
        for (int i = 0; i < dedicated.size(); i++) {
            Chunk chunk = dedicated.get(i);
            if (!chunk.coherent) {
                Vma.vmaFlushAllocation(vma, chunk.allocation, 0, VK_WHOLE_SIZE);
            }
        }
    }

    /** Destroys every buffer. The GPU must be idle. */
    void shutdown() {
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).destroy(vma);
        }
        for (int i = 0; i < dedicated.size(); i++) {
            dedicated.get(i).destroy(vma);
        }
        chunks.clear();
        dedicated.clear();
        cursor = 0;
        usedBytes = 0;
    }

    // statistics (debug mode)

    int chunkCount() {
        return chunks.size() + dedicated.size();
    }

    long usedBytes() {
        return usedBytes;
    }

    long capacityBytes() {
        long total = 0;
        for (int i = 0; i < chunks.size(); i++) {
            total += chunks.get(i).size;
        }
        for (int i = 0; i < dedicated.size(); i++) {
            total += dedicated.get(i).size;
        }
        return total;
    }

    private Chunk createChunk(int size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);

            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
            VkUtil.check(Vma.vmaCreateBuffer(vma, bufferInfo, allocationInfo, pBuffer, pAllocation, info),
                    "vmaCreateBuffer (" + name + " stream, " + size + " bytes)");
            long buffer = pBuffer.get(0);
            long allocation = pAllocation.get(0);

            long address = info.pMappedData();
            if (address == 0) {
                Vma.vmaDestroyBuffer(vma, buffer, allocation);
                throw new IllegalStateException(name + " stream buffer of " + size + " bytes is not host mapped");
            }
            IntBuffer pFlags = stack.mallocInt(1);
            Vma.vmaGetAllocationMemoryProperties(vma, allocation, pFlags);
            boolean coherent = (pFlags.get(0) & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
            if (!coherent) {
                anyNonCoherent = true;
                VkLog.LOGGER.debug("{} stream chunk is not host-coherent, flushing it before submits", name);
            }
            VkUtil.setObjectName(RenderCore.device(), VK_OBJECT_TYPE_BUFFER, buffer, name + " stream");
            return new Chunk(buffer, allocation, address, size, coherent, resets);
        }
    }

    private static final class Chunk {
        final long buffer;
        final long allocation;
        final long address;
        final int size;
        final boolean coherent;
        /** Bytes handed out in the current frame (high-water mark of the linear allocation). */
        int offset;
        long lastUsedReset;

        Chunk(long buffer, long allocation, long address, int size, boolean coherent, long createdAtReset) {
            this.buffer = buffer;
            this.allocation = allocation;
            this.address = address;
            this.size = size;
            this.coherent = coherent;
            this.lastUsedReset = createdAtReset;
        }

        StreamSlice slice(int sliceOffset, int bytes) {
            long sliceAddress = address + sliceOffset;
            return new StreamSlice(buffer, sliceOffset, sliceAddress, MemoryUtil.memByteBuffer(sliceAddress, bytes));
        }

        void destroy(long vma) {
            Vma.vmaDestroyBuffer(vma, buffer, allocation);
        }
    }
}
