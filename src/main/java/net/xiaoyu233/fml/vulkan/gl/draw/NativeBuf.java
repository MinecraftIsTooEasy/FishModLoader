package net.xiaoyu233.fml.vulkan.gl.draw;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Growable block of native (malloc) memory used for CPU-side scratch data: immediate-mode vertices, display list capture, vertex
 * conversion. Native memory is written in the platform byte order, which is little endian on every Vulkan host this port targets
 * (Windows x64, macOS arm64/x64), i.e. exactly what the GPU reads.
 */
final class NativeBuf {
    /** Start of the block (0 while nothing is allocated). The address changes whenever the block grows: re-read it after {@link #ensure}. */
    long address;
    int capacity;
    /** Append cursor in bytes. */
    int size;

    /** Makes sure at least {@code bytes} bytes are addressable (contents are kept) and returns the (possibly new) address. */
    long ensure(int bytes) {
        if (bytes > capacity) {
            long want = Math.max((long) bytes, Math.max(256L, (long) capacity * 2));
            int newCap = (int) Math.min(want, (long) Integer.MAX_VALUE - 15);
            if (newCap < bytes) {
                throw new OutOfMemoryError("native scratch buffer of " + bytes + " bytes");
            }
            long a = MemoryUtil.nmemRealloc(address, newCap);
            if (a == 0L) {
                throw new OutOfMemoryError("native scratch buffer of " + newCap + " bytes");
            }
            address = a;
            capacity = newCap;
        }
        return address;
    }

    /** Reserves {@code bytes} bytes at the end and returns the address of the reserved region. */
    long append(int bytes) {
        int end = Math.addExact(size, bytes);
        ensure(end);
        long p = address + size;
        size = end;
        return p;
    }

    void clear() {
        size = 0;
    }

    /** A direct ByteBuffer view of {@code [0, size)} (position 0, limit size); valid until the block grows or is freed. */
    ByteBuffer view() {
        return MemoryUtil.memByteBuffer(address, size);
    }

    void free() {
        if (address != 0L) {
            MemoryUtil.nmemFree(address);
        }
        address = 0L;
        capacity = 0;
        size = 0;
    }
}
