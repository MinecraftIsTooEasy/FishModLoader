package net.xiaoyu233.fml.vulkan.gl.draw;

import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.core.StreamSlice;

/**
 * Bump allocator on top of the per-frame stream rings of {@link RenderCore}: it takes one large slice at a time
 * ({@code RenderCore.streamVertex/streamIndex}, no per-draw slice objects) and hands out consecutive 4-byte aligned pieces, so draws
 * issued back to back are physically adjacent in the VkBuffer and can be merged into one draw call. Everything is reset at the start
 * of every frame (the ring memory belongs to the frame slot). Render thread only.
 */
final class StreamArena {
    private final boolean indexRing;
    private final int blockSize;

    private long buffer;
    private long baseOffset;
    private long baseAddress;
    private int capacity;
    private int used;

    /** Result of the last {@link #alloc}: VkBuffer, absolute byte offset inside it and the mapped CPU address of the piece. */
    long outBuffer;
    long outOffset;
    long outAddress;

    StreamArena(boolean indexRing, int blockSize) {
        this.indexRing = indexRing;
        this.blockSize = blockSize;
    }

    /** Reserves {@code bytes} bytes (start aligned to 4) and publishes the position in the {@code out*} fields. */
    void alloc(int bytes) {
        int start = (used + 3) & ~3;
        if (baseAddress == 0L || (long) start + bytes > capacity) {
            long want = Math.max((long) blockSize, ((long) bytes + 15L) & ~15L);
            if (want > Integer.MAX_VALUE - 15) {
                throw new OutOfMemoryError("stream allocation of " + bytes + " bytes");
            }
            int req = (int) want;
            StreamSlice slice = indexRing ? RenderCore.streamIndex(req) : RenderCore.streamVertex(req);
            buffer = slice.buffer();
            baseOffset = slice.offset();
            baseAddress = slice.address();
            capacity = req;
            start = 0;
        }
        outBuffer = buffer;
        outOffset = baseOffset + start;
        outAddress = baseAddress + start;
        used = start + bytes;
    }

    /** Forgets the current block (new frame: the ring memory is reused by the frame slot). */
    void reset() {
        baseAddress = 0L;
        buffer = 0L;
        capacity = 0;
        used = 0;
    }
}
