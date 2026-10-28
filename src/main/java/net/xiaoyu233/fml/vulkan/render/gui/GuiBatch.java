package net.xiaoyu233.fml.vulkan.render.gui;

import net.xiaoyu233.fml.vulkan.render.buffer.Batches;
import net.xiaoyu233.fml.vulkan.render.buffer.VertexConsumer;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.PoseStack;
import net.xiaoyu233.fml.vulkan.render.state.VertexFormat;

/**
 * Batched GUI rectangles (3.11, GUI-02). The current {@code Draw.color} is promoted into the vertex colours, the quads go straight to the
 * {@link Batches} consumer of the current render type, so consecutive rects of one texture/state extend one batch entry.
 * The caller sets the GL-like state (blend, texturing, bound texture) through {@code Draw} before calling. Render thread only.
 */
public final class GuiBatch {
    private GuiBatch() {
    }

    /** Untextured quad with one colour (argb); state is the caller's (texturing off). */
    public static void rect(float x0, float y0, float x1, float y1, float z, int argb) {
        int abgr = argbToAbgr(argb);
        quad(x0, y0, x1, y1, z, abgr, abgr, 0f, 0f, 0f, 0f);
    }

    /** Untextured quad with a vertical colour gradient (argb top to bottom). */
    public static void gradient(float x0, float y0, float x1, float y1, float z, int argbTop, int argbBottom) {
        quad(x0, y0, x1, y1, z, argbToAbgr(argbTop), argbToAbgr(argbBottom), 0f, 0f, 0f, 0f);
    }

    /** Textured quad with the current {@code Draw.color}; (x, y) is the top-left corner, v0 the top uv. */
    public static void textured(float x, float y, float z, float w, float h, float u0, float v0, float u1, float v1) {
        int c = Draw.colorAbgr();
        quad(x, y, x + w, y + h, z, c, c, u0, v0, u1, v1);
    }

    private static int argbToAbgr(int argb) {
        return argb & 0xFF00FF00 | (argb >> 16 & 0xFF) | (argb & 0xFF) << 16;
    }

    /** Corners in MITE order: (x0,y1) (x1,y1) (x1,y0) (x0,y0); the top colour belongs to y0 edge. */
    private static void quad(float x0, float y0, float x1, float y1, float z, int cTop, int cBottom, float u0, float v0, float u1, float v1) {
        final VertexConsumer c = Batches.consumer(Draw.currentRenderType(VertexFormat.UNIVERSAL));
        final PoseStack pose = Draw.pose();
        final float[] m = pose.top();
        final PoseStack uvStack = Draw.uvMatrix();
        final boolean uvId = uvStack.isIdentity();
        final float[] um = uvStack.top();
        final int light = Draw.lightPacked();
        final int ov = Draw.overlayArgb();
        for (int k = 0; k < 4; k++) {
            float x = k == 0 || k == 3 ? x0 : x1;
            float y = k < 2 ? y1 : y0;
            float u = k == 0 || k == 3 ? u0 : u1;
            float v = k < 2 ? v1 : v0;
            if (!uvId) {
                float nu = um[0] * u + um[4] * v + um[12];
                v = um[1] * u + um[5] * v + um[13];
                u = nu;
            }
            c.vertex(m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14],
                    k < 2 ? cBottom : cTop, u, v, light, ov);
        }
    }
}
