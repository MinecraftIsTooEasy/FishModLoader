package net.xiaoyu233.fml.vulkan.render.gpu;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.core.OffscreenTarget;
import net.xiaoyu233.fml.vulkan.core.RenderCore;

/**
 * Offscreen colour + depth target (1.3, 5.6). {@link #main()} is the phase-1 offscreen "default framebuffer" (a view of
 * {@code RenderCore}'s target, which is re-created on resize, so nothing about it is cached here). Every other target (PANORAMA, EYE0,
 * EYE1) owns images of the same formats whose framebuffer is compatible with the phase-1 render passes, so the same pipelines serve all
 * targets. A target created with {@code followsFramebuffer} is re-created at the new size when the window is resized.
 *
 * <p>Between passes the colour image is in {@code COLOR_ATTACHMENT_OPTIMAL} (also right after creation). {@link #bind()} redirects
 * draws, clears and readbacks to the target; the frame ends on MAIN again.
 */
public final class RenderTarget {
    private static final RenderTarget MAIN = new RenderTarget();

    private final String name;
    private final boolean followsFramebuffer;
    private final boolean main;
    private int width;
    private int height;
    private OffscreenTarget target;      // null for MAIN
    private boolean destroyed;

    /** The main framebuffer target. */
    public static RenderTarget main() {
        return MAIN;
    }

    private RenderTarget() {
        this.name = "main";
        this.followsFramebuffer = true;
        this.main = true;
    }

    /**
     * Creates the target (render thread, backend running). With {@code followsFramebuffer} the size arguments are ignored and the target
     * always has the size of MAIN.
     */
    public RenderTarget(String name, int width, int height, boolean followsFramebuffer) {
        if (!RenderCore.isInitialized()) {
            throw new IllegalStateException("RenderTarget " + name + " created before the Vulkan backend started");
        }
        this.name = name;
        this.followsFramebuffer = followsFramebuffer;
        this.main = false;
        if (followsFramebuffer) {
            width = RenderCore.framebufferWidth();
            height = RenderCore.framebufferHeight();
        }
        this.target = RenderCore.createTarget(width, height);
        this.width = target.width();
        this.height = target.height();
        GpuCore.register(this);
    }

    public String name() {
        return name;
    }

    public int width() {
        return main ? RenderCore.framebufferWidth() : width;
    }

    public int height() {
        return main ? RenderCore.framebufferHeight() : height;
    }

    public long colorImage() {
        return handle().colorImage();
    }

    public long framebuffer() {
        return handle().framebuffer();
    }

    /** View of the colour image (to sample the target, e.g. the anaglyph composite). */
    public long colorView() {
        return handle().colorView();
    }

    /** Makes this target the one draws, clears and readbacks go to; pending merged draws are recorded into the previous target first. */
    public void bind() {
        RenderCore.setActiveTarget(main ? null : handle());
    }

    /** Back to MAIN. */
    public static void bindMain() {
        RenderCore.setActiveTarget(null);
    }

    /** True while this target is the one draws go to. */
    public boolean isBound() {
        return RenderCore.activeTarget() == handle();
    }

    /** Releases the images once the frames in flight that may use them completed. MAIN cannot be destroyed. */
    public void destroy() {
        if (main || destroyed) {
            return;
        }
        destroyed = true;
        GpuCore.unregister(this);
        OffscreenTarget t = target;
        target = null;
        if (t != null && RenderCore.isInitialized()) {
            RenderCore.destroyTarget(t);
        }
    }

    OffscreenTarget handle() {
        if (main) {
            return RenderCore.mainTarget();
        }
        if (destroyed) {
            throw new IllegalStateException("RenderTarget " + name + " is destroyed");
        }
        return target;
    }

    /** MAIN was re-created at {@code w x h}: followers are re-created at that size. */
    void onMainResized(int w, int h) {
        if (main || destroyed || !followsFramebuffer || (w == width && h == height)) {
            return;
        }
        OffscreenTarget old = target;
        boolean wasBound = RenderCore.activeTarget() == old;
        target = RenderCore.createTarget(w, h);
        width = target.width();
        height = target.height();
        if (wasBound) {
            RenderCore.setActiveTarget(target);
        }
        RenderCore.destroyTarget(old);
        VkLog.LOGGER.debug("RenderTarget {} resized to {}x{}", name, width, height);
    }
}
