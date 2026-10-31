package net.xiaoyu233.fml.vulkan.texture;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.core.RenderCore;

/**
 * Per-frame bookkeeping of the texture store: the inline copy counter (1.11, {@code FrameStats.inlineCopies}) and the safety flush of an
 * upload batch that was left open at the end of a frame. Registered with {@link RenderCore#addListener} when the store initialises.
 */
final class TextureFrameHooks implements RenderCore.FrameListener {
    private static final TextureFrameHooks INSTANCE = new TextureFrameHooks();

    private static int current;
    private static int previous;

    private TextureFrameHooks() {
    }

    static void install() {
        RenderCore.addListener(INSTANCE);
    }

    static void reset() {
        current = 0;
        previous = 0;
    }

    /** A write to a texture was recorded on the main command buffer (the render pass was ended for it). */
    static void countInlineCopy() {
        current++;
    }

    static int currentFrame() {
        return current;
    }

    static int previousFrame() {
        return previous;
    }

    @Override
    public void onFrameBegin() {
        previous = current;
        current = 0;
    }

    @Override
    public void beforeFrameEnd() {
        if (UploadEngine.isBatching()) {
            VkLog.LOGGER.warn("Texture upload batch still open at the end of the frame: flushed");
            UploadEngine.endAllBatches();
        }
    }
}
