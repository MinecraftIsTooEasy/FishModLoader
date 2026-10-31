package net.xiaoyu233.fml.vulkan.gl.draw;

import org.lwjgl.system.MemoryUtil;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/** Address helpers for LWJGL 2 style NIO buffer arguments (LWJGL 2 only accepted direct buffers and used {@code position()} as the start). */
final class Mem {
    private Mem() {
    }

    static void checkDirect(Buffer b) {
        if (!b.isDirect()) {
            throw new IllegalArgumentException("Non-direct buffer.");
        }
    }

    /** Address of the first remaining element (base address + position * element size). */
    static long addr(ByteBuffer b) {
        checkDirect(b);
        return MemoryUtil.memAddress(b);
    }

    static long addr(ShortBuffer b) {
        checkDirect(b);
        return MemoryUtil.memAddress(b);
    }

    static long addr(IntBuffer b) {
        checkDirect(b);
        return MemoryUtil.memAddress(b);
    }

    static long addr(FloatBuffer b) {
        checkDirect(b);
        return MemoryUtil.memAddress(b);
    }

    static long addr(DoubleBuffer b) {
        checkDirect(b);
        return MemoryUtil.memAddress(b);
    }

    /** First byte after the whole backing storage (base + capacity * element size); used to bounds-check client array reads. */
    static long endOfStorage(ByteBuffer b) {
        return MemoryUtil.memAddress0(b) + b.capacity();
    }

    static long endOfStorage(ShortBuffer b) {
        return MemoryUtil.memAddress0(b) + ((long) b.capacity() << 1);
    }

    static long endOfStorage(IntBuffer b) {
        return MemoryUtil.memAddress0(b) + ((long) b.capacity() << 2);
    }

    static long endOfStorage(FloatBuffer b) {
        return MemoryUtil.memAddress0(b) + ((long) b.capacity() << 2);
    }

    static long endOfStorage(DoubleBuffer b) {
        return MemoryUtil.memAddress0(b) + ((long) b.capacity() << 3);
    }
}
