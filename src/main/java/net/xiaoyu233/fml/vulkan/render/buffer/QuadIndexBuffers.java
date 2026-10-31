package net.xiaoyu233.fml.vulkan.render.buffer;

import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.core.StaticBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;

/** Static quad index buffers (1.6): u16 pattern for 16,384 quads, u32 pattern for 262,144 quads. Created lazily on the render thread. */
public final class QuadIndexBuffers {
    public static final int MAX_QUADS_U16 = 16384, MAX_QUADS_U32 = 262144;

    private static StaticBuffer u16;
    private static StaticBuffer u32;
    /** VkDevice handle the buffers were created on; 0 when none. Buffers of another device were destroyed with it. */
    private static long device;

    private QuadIndexBuffers() {
    }

    /** VkBuffer holding the u16 pattern {@code (0,1,2,2,3,0) + 4k}; the data starts at {@link #u16Offset()}. */
    public static long u16Buffer() {
        return u16().buffer();
    }

    public static long u16Offset() {
        return u16().offset();
    }

    /** VkBuffer holding the u32 pattern; the data starts at {@link #u32Offset()}. */
    public static long u32Buffer() {
        return u32().buffer();
    }

    public static long u32Offset() {
        return u32().offset();
    }

    private static StaticBuffer u16() {
        checkDevice();
        StaticBuffer b = u16;
        if (b == null) {
            ByteBuffer data = MemoryUtil.memAlloc(MAX_QUADS_U16 * 6 * 2);
            try {
                for (int q = 0; q < MAX_QUADS_U16; q++) {
                    int base = q * 4;
                    int at = q * 12;
                    data.putShort(at, (short) base);
                    data.putShort(at + 2, (short) (base + 1));
                    data.putShort(at + 4, (short) (base + 2));
                    data.putShort(at + 6, (short) (base + 2));
                    data.putShort(at + 8, (short) (base + 3));
                    data.putShort(at + 10, (short) base);
                }
                u16 = b = RenderCore.createStatic(data, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            } finally {
                MemoryUtil.memFree(data);
            }
        }
        return b;
    }

    private static StaticBuffer u32() {
        checkDevice();
        StaticBuffer b = u32;
        if (b == null) {
            ByteBuffer data = MemoryUtil.memAlloc(MAX_QUADS_U32 * 6 * 4);
            try {
                for (int q = 0; q < MAX_QUADS_U32; q++) {
                    int base = q * 4;
                    int at = q * 24;
                    data.putInt(at, base);
                    data.putInt(at + 4, base + 1);
                    data.putInt(at + 8, base + 2);
                    data.putInt(at + 12, base + 2);
                    data.putInt(at + 16, base + 3);
                    data.putInt(at + 20, base);
                }
                u32 = b = RenderCore.createStatic(data, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            } finally {
                MemoryUtil.memFree(data);
            }
        }
        return b;
    }

    /**
     * Drops the buffers when the device changed (shutdown, then a new start). They were destroyed by {@code RenderCore.shutdown}, so they are
     * not freed again here (their release hook is a no-op for a dead device) and the next use creates new ones.
     */
    private static void checkDevice() {
        long current = currentDevice();
        if (current != device) {
            u16 = null;
            u32 = null;
            device = current;
        }
    }

    /** Handle of the current VkDevice, 0 when RenderCore is not initialised. */
    private static long currentDevice() {
        var d = RenderCore.device();
        return d == null ? 0L : d.address();
    }

    /** Frees both buffers (shutdown / device recreation); they are recreated lazily on next use. */
    public static void destroy() {
        StaticBuffer a = u16, b = u32;
        u16 = null;
        u32 = null;
        device = 0L;
        if (a != null) {
            a.free();
        }
        if (b != null) {
            b.free();
        }
    }
}
