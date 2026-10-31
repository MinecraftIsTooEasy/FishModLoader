package net.xiaoyu233.fml.vulkan.render.item;

import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.DepthFunc;
import net.xiaoyu233.fml.vulkan.render.state.Draw;

/**
 * Item glint and shared state helpers (3.9, NR-14). The glint of 3D/2D held and dropped items scrolls the glint texture through
 * {@link Draw#uvMatrix()} exactly as MITE's texture-matrix ops; the GUI glint (depth GREATER, DST_COLOR/DST_COLOR) is handled by
 * the {@code RenderItem} mixin through {@link #beginGuiGlint()} / {@link #endGuiGlint()}.
 */
public final class ItemEffects {
    /** MITE glint colour factor of {@code RenderItem.doRenderItem} (0.76). */
    public static final float WORLD_GLINT_K = 0.76F;
    public static final float UV_SCALE = 0.125F;

    private ItemEffects() {
    }

    /** MITE {@code RenderItem} world glint prologue: depth EQUAL, lighting off, blend SRC_COLOR/ONE, tint (0.5,0.25,0.8)*k. Caller binds the glint texture before. */
    public static void beginWorldGlint(float k) {
        Draw.depthFunc(DepthFunc.EQUAL);
        Draw.lighting(false);
        Draw.blend(true);
        Draw.blendFunc(BlendFactor.SRC_COLOR, BlendFactor.ONE);
        Draw.color(0.5F * k, 0.25F * k, 0.8F * k, 1.0F);
    }

    public static void endWorldGlint() {
        Draw.blend(false);
        Draw.lighting(true);
        Draw.depthFunc(DepthFunc.LEQUAL);
    }

    /** Scroll pass i (0 or 1) on the uv matrix: push, scale 1/8, translate by +-t, rotate; caller emits the item then calls {@link #endGlintPass()}. */
    public static void beginGlintPass(int pass, long systemTimeMs) {
        long period = pass == 0 ? 3000L : 4873L;
        float t = (float) (systemTimeMs % period) / (float) period * 8.0F;
        net.xiaoyu233.fml.vulkan.render.state.PoseStack uv = Draw.uvMatrix();
        uv.push();
        uv.scale(UV_SCALE, UV_SCALE, UV_SCALE);
        uv.translate(pass == 0 ? t : -t, 0.0F, 0.0F);
        uv.rotate(pass == 0 ? -50.0F : 10.0F, 0.0F, 0.0F, 1.0F);
    }

    public static void endGlintPass() {
        Draw.uvMatrix().pop();
    }

    /** GUI glint prologue: depth GREATER, lighting off, depth mask off, blend DST_COLOR/DST_COLOR, colour (0.5,0.25,0.8). */
    public static void beginGuiGlint() {
        Draw.depthFunc(DepthFunc.GREATER);
        Draw.lighting(false);
        Draw.depthMask(false);
        Draw.blend(true);
        Draw.blendFunc(BlendFactor.DST_COLOR, BlendFactor.DST_COLOR);
        Draw.color(0.5F, 0.25F, 0.8F, 1.0F);
    }

    public static void endGuiGlint() {
        Draw.blend(false);
        Draw.depthMask(true);
        Draw.lighting(true);
        Draw.depthFunc(DepthFunc.LEQUAL);
    }
}
