package net.xiaoyu233.fml.vulkan.render.text;

import net.xiaoyu233.fml.vulkan.render.gpu.DeviceFeatures;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.tess.NativeTess;
import net.xiaoyu233.fml.vulkan.render.tess.TessBridge;

/**
 * Glyph and cursor emitters of the font renderer (ARCHITECTURE-NATIVE 3.8); render thread only. All emitters use the current {@link Draw}
 * state (colour, pose, blend, alpha, fog, lightmap) exactly as the GL immediate-mode code of MITE's {@code FontRenderer} did, and write
 * quads through the render-thread {@link NativeTess} (DRAW target), so consecutive glyphs of one page merge into one ordered batch.
 * <p>
 * Shared core (written by the NR-16 lead): correct but simple bodies; the performance/merging work is a subtask (see
 * {@code GlyphBatcher} notes in the package docs of the work package).
 */
public final class TextRenderer {
    private TextRenderer() {
    }

    /**
     * One glyph quad on texture {@code textureId} (bound on unit 0 through {@link Draw#bindTexture}), corners in MITE's strip order
     * mapped to a quad: top-left {@code (x + shear, y)}, bottom-left {@code (x - shear, y + height)}, bottom-right
     * {@code (x + width - shear, y + height)}, top-right {@code (x + width + shear, y)}; uv {@code (u0,v0)} top-left to {@code (u1,v1)}
     * bottom-right. {@code shear} is 0 or 1 (italic), z = 0.
     */
    public static void glyph(int textureId, float x, float y, float width, float height, float u0, float v0, float u1, float v1, float shear) {
        Draw.bindTexture(textureId);
        if (GlyphBatcher.quad(x + shear, y, u0, v0, x - shear, y + height, u0, v1,
                x + width - shear, y + height, u1, v1, x + width + shear, y, u1, v0)) {
            return;
        }
        NativeTess t = TessBridge.renderThread();
        t.startDrawingQuads();
        t.addVertexWithUV(x + shear, y, 0.0, u0, v0);
        t.addVertexWithUV(x - shear, y + height, 0.0, u0, v1);
        t.addVertexWithUV(x + width - shear, y + height, 0.0, u1, v1);
        t.addVertexWithUV(x + width + shear, y, 0.0, u1, v0);
        t.draw();
    }

    /** Capacity of the decoration buffer in rectangles; a full buffer is flushed early. */
    private static final int SOLID_CAPACITY = 64;
    /** Pending untextured rectangles (x0, y0, x1, y1 per entry), render thread only. Colour is uniform across one pending block. */
    private static final float[] solidRects = new float[SOLID_CAPACITY * 4];
    private static int solidCount;

    /**
     * Queues an untextured quad (underline / strikethrough) in the current colour, corners in MITE's order
     * {@code (x0,y1) (x1,y1) (x1,y0) (x0,y0)}. Nothing is drawn until {@link #flushSolids()}, so a whole run of decorations costs one
     * texturing off / on pair. Adjacent or overlapping rectangles on the same band are merged. The caller must flush before the colour
     * or any other draw state changes.
     */
    public static void solidQuad(float x0, float y0, float x1, float y1) {
        int n = solidCount;
        if (n > 0) {
            int last = (n - 1) * 4;
            if (solidRects[last + 1] == y0 && solidRects[last + 3] == y1 && x0 <= solidRects[last + 2]) {
                if (x1 > solidRects[last + 2]) {
                    solidRects[last + 2] = x1;
                }
                return;
            }
        }
        if (n == SOLID_CAPACITY) {
            flushSolids();
            n = 0;
        }
        int i = n * 4;
        solidRects[i] = x0;
        solidRects[i + 1] = y0;
        solidRects[i + 2] = x1;
        solidRects[i + 3] = y1;
        solidCount = n + 1;
    }

    /**
     * Emits the queued decorations as one untextured block (texturing off once, on again after), then empties the buffer. No-op when
     * nothing is pending.
     */
    public static void flushSolids() {
        int n = solidCount;
        if (n == 0) {
            return;
        }
        solidCount = 0;
        Draw.texturing(false);
        for (int i = 0; i < n * 4; i += 4) {
            rawQuad(solidRects[i], solidRects[i + 1], solidRects[i + 2], solidRects[i + 3]);
        }
        Draw.texturing(true);
    }

    /**
     * The text-field cursor (GuiTextField.drawCursorVertical): MITE colour (0, 0, 255) with GL_COLOR_LOGIC_OP OR_REVERSE, texturing off,
     * quad {@code (x0,y1) (x1,y1) (x1,y0) (x0,y0)}. With {@code logicOp} available: {@link Draw#logicOpCursor}; otherwise the exact
     * two-pass emulation of 3.8 (blend (ONE_MINUS_DST_COLOR, ZERO) colour (1,1,1), then blend (ONE, ONE) colour (0,0,1)). Restores
     * blend / texturing state afterwards; the colour is left at (0, 0, 1, 1) as MITE leaves it.
     *
     * @return {@code true} if the logic-op pipeline was used, {@code false} for the two-pass emulation
     */
    public static boolean cursorQuad(float x0, float y0, float x1, float y1) {
        return cursorQuad(x0, y0, x1, y1, false);
    }

    /**
     * As {@link #cursorQuad(float, float, float, float)}; with {@code forceEmulation} the two-pass emulation is used even on a device
     * that enables {@code logicOp} (verification only: the emulation is the exactness fallback and must stay testable).
     *
     * @return {@code true} if the logic-op pipeline was used, {@code false} for the two-pass emulation
     */
    public static boolean cursorQuad(float x0, float y0, float x1, float y1, boolean forceEmulation) {
        Draw.texturing(false);
        try {
            if (!forceEmulation && DeviceFeatures.logicOp()) {
                Draw.color(0f, 0f, 1f, 1f);
                Draw.logicOpCursor(true);
                try {
                    rawQuad(x0, y0, x1, y1);
                } finally {
                    Draw.logicOpCursor(false);
                }
                return true;
            }
            boolean blend = Draw.blendEnabled();
            BlendFactor src = Draw.blendSrc(), dst = Draw.blendDst();
            boolean depthMask = Draw.depthMaskEnabled();
            // Both passes must be depth-tested against the same unchanged buffer, as the single logic-op draw was. Pass 1 writing depth at
            // z = 0 would make pass 2 fail a LESS test, so depth writes are off for both passes (the cursor is a colour overlay).
            Draw.depthMask(false);
            try {
                // pass 1: 1 - dst in all channels
                Draw.blend(true);
                Draw.blendFunc(BlendFactor.ONE_MINUS_DST_COLOR, BlendFactor.ZERO);
                Draw.color(1f, 1f, 1f, 1f);
                rawQuad(x0, y0, x1, y1);
                // pass 2: blue channel saturates, R/G unchanged
                Draw.blendFunc(BlendFactor.ONE, BlendFactor.ONE);
                Draw.color(0f, 0f, 1f, 1f);
                rawQuad(x0, y0, x1, y1);
            } finally {
                // blend / depth-mask state is restored even if a pass throws
                Draw.depthMask(depthMask);
                Draw.blendFunc(src, dst);
                Draw.blend(blend);
            }
            // colour left at (0,0,1,1), as MITE leaves it after glColor4f(0,0,255,255)
            Draw.color(0f, 0f, 1f, 1f);
            return false;
        } finally {
            Draw.texturing(true);
        }
    }

    private static void rawQuad(float x0, float y0, float x1, float y1) {
        if (GlyphBatcher.quad(x0, y1, 0f, 0f, x1, y1, 0f, 0f, x1, y0, 0f, 0f, x0, y0, 0f, 0f)) {
            return;
        }
        NativeTess t = TessBridge.renderThread();
        t.startDrawingQuads();
        t.addVertex(x0, y1, 0.0);
        t.addVertex(x1, y1, 0.0);
        t.addVertex(x1, y0, 0.0);
        t.addVertex(x0, y0, 0.0);
        t.draw();
    }
}
