package net.xiaoyu233.fml.vulkan.render.text;

import net.xiaoyu233.fml.vulkan.render.buffer.Batches;
import net.xiaoyu233.fml.vulkan.render.buffer.VertexConsumer;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.PoseStack;
import net.xiaoyu233.fml.vulkan.render.state.RenderType;
import net.xiaoyu233.fml.vulkan.render.state.VertexFormat;

/**
 * Direct quad emitter of the text path (ARCHITECTURE-NATIVE 3.8); render thread only, no allocation per glyph. Writes final vertices
 * (pose applied, current colour / light / overlay, no CPU lighting) straight into the batch of the current {@link RenderType}, with the
 * same arithmetic as {@code NativeTess.finalizeVertices} so the vertex data is bit-identical to the NativeTess path.
 * <p>
 * The RenderType is cached while {@link Draw#materialKey()} and the bound textures are unchanged. The consumer is cached only under a
 * {@link Batches#testCapture} sink (so N glyphs make one RecordedDraw); otherwise {@link Batches#consumer} is asked per quad, which is a
 * cheap lookup that returns the open entry's builder and stays correct when other emitters interleave.
 */
public final class GlyphBatcher {
    private static RenderType cachedType;
    private static long cachedKey;
    private static int cachedTex0 = -1, cachedTex1 = -1;
    private static VertexConsumer cachedConsumer;

    private GlyphBatcher() {
    }

    /** Drops the cached render type / consumer (call after installing or removing a test capture sink). */
    public static void invalidate() {
        cachedType = null;
        cachedConsumer = null;
    }

    /** True when quads can be emitted directly: lighting off and uv matrix identity. */
    static boolean eligible() {
        return !Draw.lightingEnabled() && Draw.uvMatrix().isIdentity();
    }

    /**
     * Emits one quad (4 vertices, corner order as given); {@code uv} false writes uv 0 (untextured). Returns false when nothing was
     * written and the caller must use the NativeTess path.
     */
    static boolean quad(float x0, float y0, float u0, float v0,
                        float x1, float y1, float u1, float v1,
                        float x2, float y2, float u2, float v2,
                        float x3, float y3, float u3, float v3) {
        if (!eligible()) {
            return false;
        }
        long key = Draw.materialKey();
        int t0 = Draw.boundTexture(0), t1 = Draw.boundTexture(1);
        boolean capturing = Batches.isTestCapturing();
        if (cachedType == null || key != cachedKey || t0 != cachedTex0 || t1 != cachedTex1) {
            cachedType = Draw.currentRenderType(VertexFormat.UNIVERSAL);
            cachedKey = key;
            cachedTex0 = t0;
            cachedTex1 = t1;
            cachedConsumer = null;
        }
        VertexConsumer c;
        if (capturing) {
            c = cachedConsumer;
            if (c == null) {
                c = cachedConsumer = Batches.consumer(cachedType);
            }
        } else {
            cachedConsumer = null;
            try {
                c = Batches.consumer(cachedType);
            } catch (UnsupportedOperationException e) {
                return false;
            }
        }
        final float[] m = Draw.pose().top();
        final int col = Draw.colorAbgr();
        final int light = Draw.lightPacked();
        final int ov = Draw.overlayArgb();
        try {
            put(c, m, x0, y0, u0, v0, col, light, ov);
        } catch (UnsupportedOperationException e) {
            return false; // backend not ready: nothing was written
        }
        put(c, m, x1, y1, u1, v1, col, light, ov);
        put(c, m, x2, y2, u2, v2, col, light, ov);
        put(c, m, x3, y3, u3, v3, col, light, ov);
        return true;
    }

    private static void put(VertexConsumer c, float[] m, float x, float y, float u, float v, int col, int light, int ov) {
        final float z = 0f;
        c.vertex(m[0] * x + m[4] * y + m[8] * z + m[12],
                m[1] * x + m[5] * y + m[9] * z + m[13],
                m[2] * x + m[6] * y + m[10] * z + m[14],
                col, u, v, light, ov);
    }
}
