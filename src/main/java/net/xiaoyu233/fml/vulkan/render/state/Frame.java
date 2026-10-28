package net.xiaoyu233.fml.vulkan.render.state;

import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.render.buffer.Batches;
import net.xiaoyu233.fml.vulkan.render.gpu.CommandStream;
import net.xiaoyu233.fml.vulkan.render.gpu.GpuTimer;
import net.xiaoyu233.fml.vulkan.render.gpu.PassUniforms;
import net.xiaoyu233.fml.vulkan.render.gpu.RenderTarget;

/**
 * Frame structure of the native renderer: phases, clears, viewport, render-target stack (1.2, 5.4). Render thread only; no allocation
 * per call. Viewports use GL conventions (origin bottom-left) and are sent to Vulkan as a negative-height viewport.
 */
public final class Frame {
    private static final int MAX_TARGETS = 8;

    private static Phase phase = Phase.GUI;

    private static float clearR, clearG, clearB, clearA = 1.0F;

    private static final int[] viewport = new int[4];
    private static boolean viewportValid;       // false: the viewport follows the full size of the current target

    private static final RenderTarget[] targetStack = new RenderTarget[MAX_TARGETS];
    private static final int[] viewportStack = new int[MAX_TARGETS * 4];
    private static final boolean[] validStack = new boolean[MAX_TARGETS];
    private static int targetDepth;
    private static RenderTarget currentTarget;  // null = MAIN

    private static boolean pending;

    /**
     * Frame-lifecycle hooks. The end of every frame records the pending batches before the present commands are recorded, which covers
     * every present path, not only FramePacer.endFrame. Batches.onFrameBegin is driven by Batches' own listener, so it is not called here
     * (calling it twice per frame would zero the draw/quad counters of the last frame).
     */
    private static final RenderCore.FrameListener LISTENER = new RenderCore.FrameListener() {
        @Override
        public void onFrameBegin() {
            onFramePresented();
        }

        @Override
        public void onCommandBufferReset() {
            reapplyViewport();
        }

        @Override
        public void beforeFrameEnd() {
            Batches.flushPhase();
        }
    };

    static {
        // Registered at class init, not on first clear/viewport/pushTarget: a frame that uses none of those must still flush at its end.
        RenderCore.addListener(LISTENER);
    }

    private Frame() {
    }

    /** Re-registers the listener if RenderCore dropped it (RenderCore.destroyAll resets the listener array); addListener is idempotent. */
    private static void ensureListener() {
        RenderCore.addListener(LISTENER);
    }

    /**
     * A fresh main command buffer has no dynamic state: CommandStream forgot its viewport, so the viewport this frame owns is recorded
     * again. Runs after CommandStream's own reset listener (registered earlier, at pipeline init).
     */
    private static void reapplyViewport() {
        if (viewportValid) {
            applyViewport();
        }
    }

    /** A frame was presented (or a new one began): nothing is pending any more. */
    public static void onFramePresented() {
        pending = false;
    }

    public static void beginPhase(Phase p) {
        Batches.flushPhase();
        phase = p;
        GpuTimer.beginPhase(p);
    }

    public static Phase phase() {
        return phase;
    }

    public static void clearColor(float r, float g, float b, float a) {
        clearR = r;
        clearG = g;
        clearB = b;
        clearA = a;
    }

    public static void clearColorDepth() {
        clear(true, true);
    }

    public static void clearDepth() {
        clear(false, true);
    }

    /**
     * glClear semantics: the whole current target is cleared (the viewport is ignored, the scissor still clips), the depth clear is
     * skipped while glDepthMask is off, and the colour clear is skipped while glColorMask disables all colour channels. A partial colour
     * mask cannot be expressed with vkCmdClearAttachments (it is not affected by colour write masks), so the colour clear is skipped
     * in that case as well; it needs a masked quad clear in RenderCore.
     */
    private static void clear(boolean color, boolean depth) {
        ensureListener();
        Batches.flushPhase();
        boolean doColor = color && Draw.colorMaskBits() == 7;
        boolean doDepth = depth && Draw.depthMaskEnabled();
        if (!doColor && !doDepth) {
            return;
        }
        pending = true;
        // Vulkan rectangle covering the full target; RenderCore.clear starts a CLEAR pass for a full colour+depth clear
        // and uses vkCmdClearAttachments otherwise.
        RenderCore.clear(doColor, doDepth, clearR, clearG, clearB, clearA, 1.0F, 0, 0, targetWidth(), targetHeight());
        // vkCmdClearAttachments is implemented by MoltenVK as a quad draw that disturbs the bound pipeline, descriptor sets, push constants
        // and dynamic state although the spec says it does not: without this the next draws after a depth-only clear (GUI over the world,
        // hand) are dropped / drawn with stale state. A full clear starts a new pass and needs nothing; the viewport is re-recorded.
        if (!(doColor && doDepth)) {
            CommandStream.invalidate();
            reapplyViewport();
        }
    }

    public static void viewport(int x, int y, int w, int h) {
        ensureListener();
        if (viewportValid && viewport[0] == x && viewport[1] == y && viewport[2] == w && viewport[3] == h) {
            return;
        }
        Batches.flushPhase();
        viewport[0] = x;
        viewport[1] = y;
        viewport[2] = w;
        viewport[3] = h;
        viewportValid = true;
        applyViewport();
    }

    public static void viewportFull() {
        viewport(0, 0, targetWidth(), targetHeight());
    }

    private static int[] currentViewport() {
        if (!viewportValid) {
            viewport[0] = 0;
            viewport[1] = 0;
            viewport[2] = targetWidth();
            viewport[3] = targetHeight();
        }
        return viewport;
    }

    private static void applyViewport() {
        int h = targetHeight();
        CommandStream.setViewport(viewport[0], h - viewport[1], viewport[2], -viewport[3]);
        PassUniforms.setViewport(viewport[2], viewport[3]);
        PassUniforms.markDirty();
    }

    /** Writes x, y, w, h at position..position+3; the position is not moved. */
    public static void copyViewport(java.nio.IntBuffer dst) {
        int[] v = currentViewport();
        int p = dst.position();
        dst.put(p, v[0]).put(p + 1, v[1]).put(p + 2, v[2]).put(p + 3, v[3]);
    }

    private static int targetWidth() {
        return currentTarget != null ? currentTarget.width() : RenderCore.activeWidth();
    }

    private static int targetHeight() {
        return currentTarget != null ? currentTarget.height() : RenderCore.activeHeight();
    }

    public static void pushTarget(RenderTarget t) {
        if (targetDepth >= MAX_TARGETS) {
            throw new IllegalStateException("render target stack overflow (" + MAX_TARGETS + ")");
        }
        ensureListener();
        Batches.flushPhase();
        int[] v = currentViewport();
        int i = targetDepth * 4;
        viewportStack[i] = v[0];
        viewportStack[i + 1] = v[1];
        viewportStack[i + 2] = v[2];
        viewportStack[i + 3] = v[3];
        validStack[targetDepth] = viewportValid;
        targetStack[targetDepth++] = currentTarget;
        pending = true;
        if (t == null) {
            RenderTarget.bindMain();
        } else {
            t.bind();
        }
        currentTarget = t;
        CommandStream.invalidate();
        viewportValid = false;
        viewportFull();
    }

    public static void popTarget() {
        if (targetDepth == 0) {
            throw new IllegalStateException("render target stack underflow");
        }
        Batches.flushPhase();
        targetDepth--;
        RenderTarget prev = targetStack[targetDepth];
        targetStack[targetDepth] = null;
        if (prev == null) {
            RenderTarget.bindMain();
        } else {
            prev.bind();
        }
        currentTarget = prev;
        CommandStream.invalidate();
        int i = targetDepth * 4;
        viewport[0] = viewportStack[i];
        viewport[1] = viewportStack[i + 1];
        viewport[2] = viewportStack[i + 2];
        viewport[3] = viewportStack[i + 3];
        viewportValid = validStack[targetDepth];
        if (viewportValid) {
            applyViewport();
        } else {
            viewportFull();
        }
    }

    /** True when something was recorded (draw, clear, target use) since the last present. */
    public static boolean hasPendingFrame() {
        Batches.flushPhase(); // record queued batch geometry so it counts as a pending frame
        return pending || CommandStream.drawCalls() > 0;
    }

    /** Records every pending batch now. */
    public static void flush() {
        Batches.flushPhase();
    }

    /** Validation-message counter hook (replaces checkGLError); no counter exists in core, so nothing is counted. */
    public static void checkErrors(String label) {
    }
}
