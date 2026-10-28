package net.xiaoyu233.fml.vulkan.render.entity;

import net.minecraft.FontRenderer;
import net.minecraft.RenderManager;
import net.minecraft.Tessellator;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.DepthFunc;
import net.xiaoyu233.fml.vulkan.render.state.Draw;

/**
 * Entity effect helpers (3.7): glint passes, hurt/colour-multiplier overlay, world-space labels, shadow and fire quads.
 * Shared signatures written by the NR-13 lead; bodies belong to the subtasks named per method.
 */
public final class EntityEffects {
    private EntityEffects() {
    }

    /**
     * Glint pass state (MITE {@code RendererLivingEntity} enchanted pass loop body): blend on, depth EQUAL, depth mask off, lighting off,
     * colour (0.5,0.25,0.8)*0.76, blend SRC_COLOR,ONE, uvMatrix = identity, scale 1/3, rotate 30-60*pass about Z, translate (0, t) with
     * {@code t = time * (0.001 + pass * 0.003) * 20}. Self-contained per pass; pair every call with {@link #endGlint}.
     */
    public static void beginGlint(int pass, float time) {
        Draw.blend(true);
        Draw.depthFunc(DepthFunc.EQUAL);
        Draw.depthMask(false);
        Draw.lighting(false);
        float k = 0.76F;
        Draw.color(0.5F * k, 0.25F * k, 0.8F * k, 1.0F);
        Draw.blendFunc(BlendFactor.SRC_COLOR, BlendFactor.ONE);
        Draw.uvMatrix().loadIdentity();
        float t = time * (0.001F + pass * 0.003F) * 20.0F;
        float s = 0.33333334F;
        Draw.uvMatrix().scale(s, s, s);
        Draw.uvMatrix().rotate(30.0F - pass * 60.0F, 0.0F, 0.0F, 1.0F);
        Draw.uvMatrix().translate(0.0F, t, 0.0F);
    }

    /** Restores what MITE restores after the glint loop: colour white, uv identity, depth mask on, lighting on, blend off, depth LEQUAL. */
    public static void endGlint() {
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
        Draw.uvMatrix().loadIdentity();
        Draw.depthMask(true);
        Draw.lighting(true);
        Draw.blend(false);
        Draw.depthFunc(DepthFunc.LEQUAL);
    }

    /**
     * Sets {@code Draw.overlay} from MITE's hurt/death flash (colour (brightness,0,0), alpha 0.4; pass a negative brightness when there is no
     * hurt/death flash) and {@code getColorMultiplier} (rgba in [0,1]; null or alpha 0 = none). Both layers are source-alpha blended in MITE,
     * one over the other, so they are composed here into one overlay colour/alpha. No layer at all -> overlayNone.
     */
    public static void overlayFromHurt(float brightness, float[] multiplierRgba) {
        float a = 0.0F;
        float r = 0.0F;
        float g = 0.0F;
        float b = 0.0F;
        if (brightness >= 0.0F) {
            a = 0.4F;
            r = brightness;
        }
        if (multiplierRgba != null && multiplierRgba.length >= 4 && multiplierRgba[3] > 0.0F) {
            float a2 = multiplierRgba[3];
            float outA = 1.0F - (1.0F - a) * (1.0F - a2);
            r = (multiplierRgba[0] * a2 + r * a * (1.0F - a2)) / outA;
            g = (multiplierRgba[1] * a2 + g * a * (1.0F - a2)) / outA;
            b = (multiplierRgba[2] * a2 + b * a * (1.0F - a2)) / outA;
            a = outA;
        }
        if (a <= 0.0F) {
            Draw.overlayNone();
        } else {
            Draw.overlay(r, g, b, a);
        }
    }

    /**
     * World-mode label (RendererLivingEntity.renderLivingLabel / the sneaking branch of passSpecialRender): billboard, background quad, text
     * two passes. {@code x,y,z} are the final translation (already including height + 0.5), {@code viewerPitch} is signed for the
     * third-person-front flip, {@code maxDistanceSq} is only informational (the distance test stays with the caller).
     */
    public static void drawLabel(String text, double x, double y, double z, float viewerYaw, float viewerPitch, float scale, int maxDistanceSq, boolean sneaking) {
        FontRenderer font = RenderManager.instance.getFontRenderer();
        int half = font.getStringWidth(text) / 2;
        Draw.pushPose();
        Draw.translate((float) x + 0.0F, (float) y, (float) z);
        Draw.normal(0.0F, 1.0F, 0.0F);
        Draw.rotate(-viewerYaw, 0.0F, 1.0F, 0.0F);
        Draw.rotate(viewerPitch, 1.0F, 0.0F, 0.0F);
        Draw.scale(-scale, -scale, scale);
        Draw.lighting(false);
        if (sneaking) {
            Draw.translate(0.0F, 0.25F / scale, 0.0F);
            Draw.depthMask(false);
            Draw.blend(true);
            Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
            Tessellator tess = Tessellator.instance;
            Draw.texturing(false);
            tess.startDrawingQuads();
            tess.setColorRGBA_F(0.0F, 0.0F, 0.0F, 0.25F);
            tess.addVertex(-half - 1, -1.0, 0.0);
            tess.addVertex(-half - 1, 8.0, 0.0);
            tess.addVertex(half + 1, 8.0, 0.0);
            tess.addVertex(half + 1, -1.0, 0.0);
            tess.draw();
            Draw.texturing(true);
            Draw.depthMask(true);
            font.drawString(text, -half, 0, 553648127);
        } else {
            Draw.depthMask(false);
            Draw.depthTest(false);
            Draw.blend(true);
            Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
            Tessellator tess = Tessellator.instance;
            byte yOff = 0;
            if (text.equals("deadmau5")) {
                yOff = -10;
            }
            Draw.texturing(false);
            tess.startDrawingQuads();
            tess.setColorRGBA_F(0.0F, 0.0F, 0.0F, 0.25F);
            tess.addVertex(-half - 1, -1 + yOff, 0.0);
            tess.addVertex(-half - 1, 8 + yOff, 0.0);
            tess.addVertex(half + 1, 8 + yOff, 0.0);
            tess.addVertex(half + 1, -1 + yOff, 0.0);
            tess.draw();
            Draw.texturing(true);
            font.drawString(text, -half, yOff, 553648127);
            Draw.depthTest(true);
            Draw.depthMask(true);
            font.drawString(text, -half, yOff, -1);
        }
        Draw.lighting(true);
        Draw.blend(false);
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
        Draw.popPose();
    }
}
