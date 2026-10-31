package net.xiaoyu233.fml.vulkan.render.buffer;

import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.render.gpu.GpuAllocation;
import net.xiaoyu233.fml.vulkan.render.gpu.GpuArena;
import org.lwjgl.vulkan.VK10;

/** GPU-resident object-space geometry (1.6). UNIVERSAL vertices live in one shared arena. Render thread only. */
public final class StaticMesh {
    private static final long PAGE_BYTES = 16L << 20;
    private static GpuArena arena;
    /** VkDevice handle the {@link #arena} was created on; 0 when none. An arena of another device was destroyed with that device. */
    private static long arenaDevice;

    private final int quads;
    /** GPU storage in the static-mesh arena (UNIVERSAL vertices, 32 bytes each, 4 per quad); null for a mesh with no quads. */
    final GpuAllocation allocation;
    boolean destroyed;

    StaticMesh(int quads, GpuAllocation allocation) {
        this.quads = quads;
        this.allocation = allocation;
    }

    /**
     * The shared arena of the current device, created lazily. After a shutdown or device re-creation the old arena is gone (destroyed by
     * {@code GpuCore.shutdown}), so a new one is created; throws when the GPU core is not initialised.
     */
    static GpuArena arena() {
        long device = currentDevice();
        if (device == 0L) {
            throw new IllegalStateException("RenderCore is not initialized");
        }
        GpuArena a = arena;
        if (a == null || arenaDevice != device) {
            a = new GpuArena("static-meshes", VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, PAGE_BYTES, 32);
            arena = a;
            arenaDevice = device;
        }
        return a;
    }

    /** Handle of the current VkDevice, 0 when RenderCore is not initialised. */
    private static long currentDevice() {
        var d = RenderCore.device();
        return d == null ? 0L : d.address();
    }

    public int quadCount() {
        return quads;
    }

    public GpuAllocation allocation() {
        return allocation;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        // Memory of an arena of another device went down with that device; freeing it would only log a foreign-allocation error.
        if (allocation != null && arena != null && arenaDevice != 0L && arenaDevice == currentDevice()) {
            arena.free(allocation);
        }
    }
}
