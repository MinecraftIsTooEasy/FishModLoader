package net.xiaoyu233.fml.vulkan.render;

import net.xiaoyu233.fml.vulkan.VulkanConfig;

/**
 * Init/shutdown orchestration and switches of the native renderer (1.1). The start order is {@code GpuCore, PipelineLibrary, Textures,
 * Draw, Batches, TessBridge, MeshWorkers, TerrainRenderer, GLAudit/StateMirror} and the shutdown reverses it; the NR-00 skeleton
 * has nothing to start yet, so both calls only track the state. NR-28 maintains this class and wires it behind
 * {@code VulkanBackend.start}.
 */
public final class NativeRenderer {
    private static volatile boolean initialized;

    private NativeRenderer() {
    }

    /** Master switch: {@code fml.vulkan.native} (default true). */
    public static boolean enabled() {
        return VulkanConfig.nativeEnabled();
    }

    /** True when the native renderer is enabled and {@code area} is not listed in {@code fml.vulkan.native.off}. */
    public static boolean areaEnabled(String area) {
        return VulkanConfig.areaActive(area);
    }

    /** Starts the native renderer on the render thread after the phase-1 backend; no-op while the owners have not landed. */
    public static void init() {
        net.xiaoyu233.fml.vulkan.render.tess.TessBridge.setRenderThread(Thread.currentThread());
        net.xiaoyu233.fml.vulkan.render.gpu.PipelineLibrary.init();
        // Class init of Draw/Batches registers the static draw handler and frame listeners before the first Tessellator.draw
        net.xiaoyu233.fml.vulkan.render.state.Draw.activeUnit();
        net.xiaoyu233.fml.vulkan.render.buffer.Batches.drawsLastFrame();
        net.xiaoyu233.fml.vulkan.render.mesh.MeshWorkers.start(VulkanConfig.meshThreads());
        initialized = true;
    }

    /** Reverse of {@link #init()}; runs after {@code vkDeviceWaitIdle}. No-op while the owners have not landed. */
    public static void shutdown() {
        initialized = false;
        try {
            net.xiaoyu233.fml.vulkan.render.mesh.MeshWorkers.shutdown();
        } catch (Throwable ignored) {
        }
        try {
            net.xiaoyu233.fml.vulkan.render.terrain.TerrainRenderer.shutdown();
        } catch (Throwable ignored) {
        }
        net.xiaoyu233.fml.vulkan.render.gpu.PipelineLibrary.shutdown();
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
