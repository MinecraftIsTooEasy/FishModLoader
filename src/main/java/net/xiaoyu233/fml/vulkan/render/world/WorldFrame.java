package net.xiaoyu233.fml.vulkan.render.world;

import net.xiaoyu233.fml.vulkan.render.gpu.RenderTarget;
import net.xiaoyu233.fml.vulkan.render.state.Frame;

/**
 * Eye loop support of {@code EntityRenderer.renderWorld} (NR-12; 1.2 steps 2, 18): anaglyph eye targets EYE0/EYE1 and the BLIT composite
 * into MAIN. With anaglyph off every method is a no-op and nothing is allocated. Render thread only.
 *
 * <p><b>Known gap:</b> the BLIT program (blit.vert/.frag, spec constant ANAGLYPH) and its pipeline exist in {@code PipelineLibrary}, but
 * there is no merged API yet to bind two {@link RenderTarget} colour views as descriptor sets 1/2 and to select the ANAGLYPH
 * specialisation (the pipeline key has no such bit, no {@code Textures}/{@code CommandStream} helper samples a render target). Until
 * that exists ({@link #COMPOSITE_AVAILABLE} = true) the eye loop falls back to rendering both eyes straight into MAIN with the per-eye colour
 * masks (see {@link #usesTargets()}). This is NOT identical to MITE: the colour clear of eye 1 is replaced by a depth-only clear (the
 * masked colour clear does not exist yet), so eye 1's red channel keeps eye 0's clear value. See the clear in
 * {@code EntityRendererFrameWorldMixin.renderWorld}.
 */
public final class WorldFrame {
    /** Flip to true once a BLIT-with-two-sampled-targets API is available; enables EYE0/EYE1 and {@link #compositeAnaglyph()}. */
    private static final boolean COMPOSITE_AVAILABLE = false;

    private static RenderTarget eye0;
    private static RenderTarget eye1;
    private static boolean active;

    private WorldFrame() {
    }

    /** True when anaglyph eyes render into their own targets (otherwise the caller applies MITE's colour masks on MAIN). */
    public static boolean usesTargets() {
        return COMPOSITE_AVAILABLE;
    }

    /** True while eye targets are bound (between {@link #beginEye} and {@link #endEye}). */
    public static boolean eyeActive() {
        return active;
    }

    /** Anaglyph: pushes the (lazily created, follows-framebuffer) target of eye 0/1. No-op when anaglyph is off. */
    public static void beginEye(int eye, boolean anaglyph) {
        if (!anaglyph || !COMPOSITE_AVAILABLE) {
            return;
        }
        RenderTarget t;
        if (eye == 0) {
            if (eye0 == null) {
                eye0 = new RenderTarget("EYE0", 0, 0, true);
            }
            t = eye0;
        } else {
            if (eye1 == null) {
                eye1 = new RenderTarget("EYE1", 0, 0, true);
            }
            t = eye1;
        }
        Frame.pushTarget(t);
        active = true;
    }

    /** Pops the eye target pushed by {@link #beginEye}. */
    public static void endEye(int eye, boolean anaglyph) {
        if (!anaglyph || !COMPOSITE_AVAILABLE || !active) {
            return;
        }
        Frame.popTarget();
        active = false;
    }

    /** After both eyes: writes (EYE1.r, EYE0.g, EYE0.b) into MAIN with the BLIT program, before the HUD. */
    public static void compositeAnaglyph() {
        if (!COMPOSITE_AVAILABLE || eye0 == null || eye1 == null) {
            return;
        }
        // TODO(NR-02 follow-up): BLIT pass with eye0.colorView() in set 1 and eye1.colorView() in set 2, ANAGLYPH specialisation.
    }

    /** Destroys the eye targets (shutdown / anaglyph turned off). */
    public static void release() {
        active = false;
        if (eye0 != null) {
            eye0.destroy();
            eye0 = null;
        }
        if (eye1 != null) {
            eye1.destroy();
            eye1 = null;
        }
    }
}
