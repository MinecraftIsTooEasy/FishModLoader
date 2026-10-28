package net.xiaoyu233.fml.vulkan.render.screen;

import net.minecraft.GuiMainMenu;
import net.minecraft.MathHelper;
import net.minecraft.Minecraft;
import net.minecraft.ResourceLocation;
import net.minecraft.Tessellator;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.render.gpu.RenderTarget;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Frame;
import net.xiaoyu233.fml.vulkan.render.texture.Textures;

/**
 * Main-menu panorama (3.11). Contract: {@link #render} replaces {@code GuiMainMenu.renderSkybox}: when the panorama has to be redrawn
 * ({@link #needsRedraw}) it renders drawPanorama (64 passes) into the PANORAMA target (256x256) and runs the 8 copy+blur steps
 * ({@code Textures.copyFromTarget}, the sanctioned inline copy), then draws the cached viewport texture as the full-screen quad. Render thread only.
 */
public final class Panorama {
    /** Seconds after the last use when the PANORAMA target is destroyed (1.3). */
    public static final long IDLE_RELEASE_MS = 10_000L;
    public static final int SIZE = 256;

    private static RenderTarget target;
    private static int lastTimer = Integer.MIN_VALUE;
    private static long lastUseMs;

    private Panorama() {
    }

    /** Quantises the partial-tick term to quarter steps (D10). */
    public static float quantize(float partialTicks) {
        return Math.min(3, (int) (partialTicks * 4.0F)) * 0.25F;
    }

    /** Frame-begin hook that releases the PANORAMA target after {@link #IDLE_RELEASE_MS} without use, also while no menu is open. */
    private static final RenderCore.FrameListener IDLE_LISTENER = new RenderCore.FrameListener() {
        @Override
        public void onFrameBegin() {
            tickIdle();
        }
    };

    /** True when panoramaTimer advanced since the cached result was rendered, or no result exists (once per tick, 3.11). */
    public static boolean needsRedraw(int panoramaTimer) {
        return target == null || panoramaTimer != lastTimer;
    }

    /** Remembers the panoramaTimer the cached texture was rendered for. */
    static void markRendered(int panoramaTimer) {
        lastTimer = panoramaTimer;
    }

    /** Lazily created 256x256 PANORAMA target; registers the idle-release listener once. */
    static RenderTarget target() {
        if (target == null) {
            target = new RenderTarget("PANORAMA", SIZE, SIZE, false);
            lastUseMs = System.currentTimeMillis();
            RenderCore.addListener(IDLE_LISTENER);
        }
        return target;
    }

    /**
     * Entry point called by GuiMainMenuScreenMixin for {@code renderSkybox(int, int, float)}.
     *
     * @param panoramaTimer value of the private field {@code panoramaTimer}
     * @param paths         {@code titlePanoramaPaths}
     * @param viewportTex   GL id of the dynamic texture {@code field_110351_G} copied into by the blur steps
     * @param width         {@code GuiMainMenu.width}
     * @param height        {@code GuiMainMenu.height}
     * @param zLevel        {@code Gui.zLevel}
     */
    public static void render(GuiMainMenu menu, float partialTicks, int panoramaTimer, ResourceLocation[] paths, int viewportTex, int width,
                              int height, float zLevel) {
        long now = System.currentTimeMillis();
        if (needsRedraw(panoramaTimer)) {
            float pt = quantize(partialTicks);
            RenderTarget t = target();
            Frame.pushTarget(t);
            drawPanorama(panoramaTimer, pt, paths);
            // MITE: each rotateAndBlurSkybox copies the framebuffer into the viewport texture first, then blends its quads.
            // The 8th step's quads are never copied, so viewportTex holds exactly 7 blurs after the loop.
            for (int i = 0; i < 8; i++) {
                blurStep(viewportTex, width, height, zLevel);
            }
            Frame.popTarget();
            markRendered(panoramaTimer);
        }
        lastUseMs = now;
        drawFinal(viewportTex, width, height, zLevel);
    }

    private static void drawPanorama(int panoramaTimer, float pt, ResourceLocation[] paths) {
        Tessellator tess = Tessellator.instance;
        Minecraft mc = Minecraft.getMinecraft();
        Draw.projection().push();
        Draw.projection().loadIdentity();
        Draw.projection().perspective(120.0F, 1.0F, 0.05F, 10.0F);
        Draw.pose().push();
        Draw.pose().loadIdentity();
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
        Draw.rotate(180.0F, 1.0F, 0.0F, 0.0F);
        Draw.blend(true);
        Draw.alphaTest(false);
        Draw.cull(false);
        Draw.depthMask(false);
        Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
        final int n = 8;
        float timerPartial = panoramaTimer + pt;
        float rx = MathHelper.sin(timerPartial / 400.0F) * 25.0F + 20.0F;
        float ry = -timerPartial * 0.1F;
        for (int pass = 0; pass < n * n; pass++) {
            Draw.pushPose();
            float tx = ((float) (pass % n) / n - 0.5F) / 64.0F;
            float ty = ((float) (pass / n) / n - 0.5F) / 64.0F;
            Draw.translate(tx, ty, 0.0F);
            Draw.rotate(rx, 1.0F, 0.0F, 0.0F);
            Draw.rotate(ry, 0.0F, 1.0F, 0.0F);
            for (int face = 0; face < 6; face++) {
                Draw.pushPose();
                if (face == 1) {
                    Draw.rotate(90.0F, 0.0F, 1.0F, 0.0F);
                }
                if (face == 2) {
                    Draw.rotate(180.0F, 0.0F, 1.0F, 0.0F);
                }
                if (face == 3) {
                    Draw.rotate(-90.0F, 0.0F, 1.0F, 0.0F);
                }
                if (face == 4) {
                    Draw.rotate(90.0F, 1.0F, 0.0F, 0.0F);
                }
                if (face == 5) {
                    Draw.rotate(-90.0F, 1.0F, 0.0F, 0.0F);
                }
                mc.getTextureManager().bindTexture(paths[face]);
                tess.startDrawingQuads();
                tess.setColorRGBA_I(16777215, 255 / (pass + 1));
                tess.addVertexWithUV(-1.0, -1.0, 1.0, 0.0, 0.0);
                tess.addVertexWithUV(1.0, -1.0, 1.0, 1.0, 0.0);
                tess.addVertexWithUV(1.0, 1.0, 1.0, 1.0, 1.0);
                tess.addVertexWithUV(-1.0, 1.0, 1.0, 0.0, 1.0);
                tess.draw();
                Draw.popPose();
            }
            Draw.popPose();
            Draw.colorMask(true, true, true, false);
        }
        tess.setTranslation(0.0, 0.0, 0.0);
        Draw.colorMask(true, true, true, true);
        Draw.projection().pop();
        Draw.pose().pop();
        Draw.depthMask(true);
        Draw.cull(true);
        Draw.alphaTest(true);
        Draw.depthTest(true);
    }

    /** rotateAndBlurSkybox: copy the target into the viewport texture, then blend three shifted quads back (current matrices kept). */
    private static void blurStep(int viewportTex, int width, int height, float zLevel) {
        Textures.bind(viewportTex);
        Textures.copyFromTarget(target, 0, 0, viewportTex, 0, 0, SIZE, SIZE);
        Draw.blend(true);
        Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
        Draw.colorMask(true, true, true, false);
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        for (int i = 0; i < 3; i++) {
            tess.setColorRGBA_F(1.0F, 1.0F, 1.0F, 1.0F / (i + 1));
            float off = (i - 3 / 2) / 256.0F;
            tess.addVertexWithUV(width, height, zLevel, 0.0F + off, 0.0);
            tess.addVertexWithUV(width, 0.0, zLevel, 1.0F + off, 0.0);
            tess.addVertexWithUV(0.0, 0.0, zLevel, 1.0F + off, 1.0);
            tess.addVertexWithUV(0.0, height, zLevel, 0.0F + off, 1.0);
        }
        tess.draw();
        Draw.colorMask(true, true, true, true);
    }

    /** Final full-screen quad of renderSkybox with the cached viewport texture. */
    private static void drawFinal(int viewportTex, int width, int height, float zLevel) {
        Textures.bind(viewportTex);
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        float s = width > height ? 120.0F / width : 120.0F / height;
        float v6 = height * s / 256.0F;
        float v7 = width * s / 256.0F;
        Textures.setFilter(viewportTex, true);
        tess.setColorRGBA_F(1.0F, 1.0F, 1.0F, 1.0F);
        tess.addVertexWithUV(0.0, height, zLevel, 0.5F - v6, 0.5F + v7);
        tess.addVertexWithUV(width, height, zLevel, 0.5F - v6, 0.5F - v7);
        tess.addVertexWithUV(width, 0.0, zLevel, 0.5F + v6, 0.5F - v7);
        tess.addVertexWithUV(0.0, 0.0, zLevel, 0.5F + v6, 0.5F + v7);
        tess.draw();
    }

    public static void render(GuiMainMenu menu, float partialTicks) {
        throw new UnsupportedOperationException("NR-18 panorama: use the full overload from GuiMainMenuScreenMixin");
    }

    /** Destroys the target when idle for {@link #IDLE_RELEASE_MS}; called once per frame from {@link #IDLE_LISTENER} (render thread). */
    public static void tickIdle() {
        if (target != null && System.currentTimeMillis() - lastUseMs > IDLE_RELEASE_MS) {
            release();
        }
    }

    public static void release() {
        if (target != null) {
            target.destroy();
            target = null;
        }
        lastTimer = Integer.MIN_VALUE;
    }
}
