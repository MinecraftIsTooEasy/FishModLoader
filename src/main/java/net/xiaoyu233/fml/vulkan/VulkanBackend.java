package net.xiaoyu233.fml.vulkan;

import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.gl.draw.DrawSystem;
import net.xiaoyu233.fml.vulkan.gl.state.GLState;
import net.xiaoyu233.fml.vulkan.pipeline.Pipelines;
import net.xiaoyu233.fml.vulkan.render.NativeRenderer;
import net.xiaoyu233.fml.vulkan.render.gpu.GpuCore;
import net.xiaoyu233.fml.vulkan.texture.TextureManager;
import org.lwjgl.vulkan.VK10;

/**
 * Bootstrap of the whole backend, called by module E ({@code Display.create}/{@code Display.destroy}) on the render thread.
 */
public final class VulkanBackend {
    // number of completed init steps: RenderCore 1, Pipelines 2, TextureManager 3, GLState 4, DrawSystem 5, GpuCore 6, NativeRenderer 7
    private static final int STEP_RENDER_CORE = 1;
    private static final int STEP_PIPELINES = 2;
    private static final int STEP_TEXTURES = 3;
    private static final int STEP_GL_STATE = 4;
    private static final int STEP_DRAW = 5;
    private static final int STEP_GPU_CORE = 6;
    private static final int STEP_NATIVE = 7;

    private static volatile boolean running;

    private VulkanBackend() {
    }

    /**
     * RenderCore.init -> Pipelines.init -> TextureManager.init -> GLState.init -> DrawSystem.init (the phase-1 modules), then
     * GpuCore.init and, with {@code fml.vulkan.native=true}, NativeRenderer.init (ARCHITECTURE-NATIVE 1.1 start order).
     */
    public static synchronized void start(long glfwWindow, int framebufferWidth, int framebufferHeight) {
        if (running) {
            VkLog.LOGGER.warn("VulkanBackend.start() called while the backend is already running, ignored");
            return;
        }
        VkLog.LOGGER.info("Starting Vulkan backend (framebuffer {}x{})", framebufferWidth, framebufferHeight);
        int done = 0;
        try {
            RenderCore.init(glfwWindow, framebufferWidth, framebufferHeight);
            done = STEP_RENDER_CORE;
            Pipelines.init();
            done = STEP_PIPELINES;
            TextureManager.init();
            done = STEP_TEXTURES;
            GLState.init();
            done = STEP_GL_STATE;
            DrawSystem.init();
            done = STEP_DRAW;
            GpuCore.init();
            done = STEP_GPU_CORE;
            if (NativeRenderer.enabled()) {
                NativeRenderer.init();
                done = STEP_NATIVE;
            }
        } catch (Throwable t) {
            VkLog.LOGGER.error("Vulkan backend failed to start", t);
            teardown(done);
            throw t;
        }
        running = true;
    }

    /** Reverse order of {@link #start}; waits for the device to become idle first. No-op when not running. */
    public static synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        VkLog.LOGGER.info("Stopping Vulkan backend");
        teardown(STEP_NATIVE);
    }

    public static boolean isRunning() {
        return running;
    }

    private static void teardown(int done) {
        if (done >= STEP_RENDER_CORE) {
            waitDeviceIdle();
        }
        if (done >= STEP_NATIVE) {
            shutdownStep("NativeRenderer", NativeRenderer::shutdown);
        }
        if (done >= STEP_GPU_CORE) {
            shutdownStep("GpuCore", GpuCore::shutdown);
        }
        if (done >= STEP_DRAW) {
            shutdownStep("DrawSystem", DrawSystem::shutdown);
        }
        // GLState has no native resources: nothing to shut down
        if (done >= STEP_TEXTURES) {
            shutdownStep("TextureManager", TextureManager::shutdown);
        }
        if (done >= STEP_PIPELINES) {
            shutdownStep("Pipelines", Pipelines::shutdown);
        }
        if (done >= STEP_RENDER_CORE) {
            shutdownStep("RenderCore", RenderCore::shutdown);
        }
    }

    private static void waitDeviceIdle() {
        try {
            if (RenderCore.isInitialized()) {
                int result = VK10.vkDeviceWaitIdle(RenderCore.device());
                if (result != VK10.VK_SUCCESS) {
                    VkLog.LOGGER.warn("vkDeviceWaitIdle failed during shutdown: VkResult {}", result);
                }
            }
        } catch (Throwable t) {
            VkLog.LOGGER.warn("Waiting for the Vulkan device to become idle failed", t);
        }
    }

    private static void shutdownStep(String name, Runnable shutdown) {
        try {
            shutdown.run();
        } catch (Throwable t) {
            VkLog.LOGGER.error("{} shutdown failed", name, t);
        }
    }
}
