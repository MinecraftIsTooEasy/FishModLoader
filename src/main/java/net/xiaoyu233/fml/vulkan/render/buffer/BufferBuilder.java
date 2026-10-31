package net.xiaoyu233.fml.vulkan.render.buffer;

import net.xiaoyu233.fml.vulkan.render.state.VertexFormat;
import org.lwjgl.system.MemoryUtil;

/**
 * Off-heap, growable vertex block of one vertex format (1.6). The memory management is complete in the NR-00 skeleton (it has no GPU
 * dependency); writing a vertex through {@link #vertex} is NR-03's.
 */
public final class BufferBuilder implements VertexConsumer {
    private final VertexFormat format;
    private long address;
    private int capacityBytes;
    private int bytes;

    public BufferBuilder(VertexFormat format, int initialBytes) {
        this.format = format;
        this.capacityBytes = Math.max(initialBytes, format.stride() * 4);
        this.address = MemoryUtil.nmemAlloc(capacityBytes);
        if (address == 0L) {
            throw new OutOfMemoryError("BufferBuilder: cannot allocate " + capacityBytes + " bytes");
        }
    }

    public VertexFormat format() {
        return format;
    }

    /** Address of {@code vertices * stride} writable bytes at the end of the block; grows the block when needed. */
    public long reserve(int vertices) {
        long need = (long) bytes + (long) vertices * format.stride();
        if (need > Integer.MAX_VALUE) {
            throw new OutOfMemoryError("BufferBuilder exceeds 2 GiB");
        }
        if (need > capacityBytes) {
            long grown = Math.max(need, (long) capacityBytes * 2);
            int newCapacity = (int) Math.min(grown, Integer.MAX_VALUE);
            long moved = MemoryUtil.nmemRealloc(address, newCapacity);
            if (moved == 0L) {
                throw new OutOfMemoryError("BufferBuilder: cannot grow to " + newCapacity + " bytes");
            }
            address = moved;
            capacityBytes = newCapacity;
        }
        long at = address + bytes;
        bytes = (int) need;
        return at;
    }

    public int vertexCount() {
        return bytes / format.stride();
    }

    public int bytes() {
        return bytes;
    }

    public long address() {
        return address;
    }

    /** Forgets the content, keeps the memory for reuse. */
    public void clear() {
        bytes = 0;
    }

    /** Releases the memory; the builder must not be used afterwards. */
    public void free() {
        if (address != 0L) {
            MemoryUtil.nmemFree(address);
            address = 0L;
            capacityBytes = 0;
            bytes = 0;
        }
    }

    @Override
    public void vertex(float x, float y, float z, int abgr, float u, float v, int packedLight, int overlayArgb) {
        if (format != VertexFormat.UNIVERSAL) {
            throw new IllegalStateException("BufferBuilder.vertex() writes UNIVERSAL vertices only, this builder has format " + format
                    + (format == VertexFormat.LINE ? " (use line())" : " (written by its dedicated producer through reserve())"));
        }
        long p = reserve(1);
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
        MemoryUtil.memPutInt(p + 12, abgr);
        MemoryUtil.memPutFloat(p + 16, u);
        MemoryUtil.memPutFloat(p + 20, v);
        MemoryUtil.memPutInt(p + 24, packedLight);
        // ARGB int -> bytes R,G,B,A in memory order (little-endian int A<<24 | B<<16 | G<<8 | R)
        MemoryUtil.memPutInt(p + 28, (overlayArgb & 0xFF00FF00) | ((overlayArgb >> 16) & 0xFF) | ((overlayArgb & 0xFF) << 16));
    }

    /** LINE format: one segment = 4 vertices (a-, a+, b+, b-), each with its own and the other endpoint and side -1/+1. */
    @Override
    public void line(float x0, float y0, float z0, float x1, float y1, float z1, int abgr) {
        if (format != VertexFormat.LINE) {
            throw new IllegalStateException("BufferBuilder.line() needs a LINE builder, this builder has format " + format);
        }
        long p = reserve(4);
        linePut(p, x0, y0, z0, abgr, x1, y1, z1, -1);
        linePut(p + 32, x0, y0, z0, abgr, x1, y1, z1, 1);
        linePut(p + 64, x1, y1, z1, abgr, x0, y0, z0, 1);
        linePut(p + 96, x1, y1, z1, abgr, x0, y0, z0, -1);
    }

    private static void linePut(long p, float x, float y, float z, int abgr, float ox, float oy, float oz, int side) {
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
        MemoryUtil.memPutInt(p + 12, abgr);
        MemoryUtil.memPutFloat(p + 16, ox);
        MemoryUtil.memPutFloat(p + 20, oy);
        MemoryUtil.memPutFloat(p + 24, oz);
        MemoryUtil.memPutInt(p + 28, side & 0xFF); // i8 side, 3 pad bytes zero
    }
}
