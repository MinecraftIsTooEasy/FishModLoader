package net.xiaoyu233.fml.vulkan.mixin.nat.world;

import net.minecraft.ActiveRenderInfo;
import net.minecraft.ClippingHelperImpl;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.EffectRenderer;
import net.minecraft.EntityLivingBase;
import net.minecraft.EntityPlayer;
import net.minecraft.EntityRenderer;
import net.minecraft.Frustrum;
import net.minecraft.Material;
import net.minecraft.Minecraft;
import net.minecraft.Potion;
import net.minecraft.RenderGlobal;
import net.minecraft.ReportedException;
import net.minecraft.ScaledResolution;
import net.minecraft.Tessellator;
import net.minecraft.TextureMap;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Frame;
import net.xiaoyu233.fml.vulkan.render.state.Lighting;
import net.xiaoyu233.fml.vulkan.render.state.Phase;
import net.xiaoyu233.fml.vulkan.render.terrain.TerrainRenderer;
import net.xiaoyu233.fml.vulkan.render.world.ShadowCheck;
import net.xiaoyu233.fml.vulkan.render.world.WorldFrame;
import java.util.concurrent.Callable;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "world" (NR-12), frame part: updateCameraAndRender, renderWorld (see {@link EntityRendererWorldMixin}). */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererFrameWorldMixin {
    @Shadow private Minecraft mc;
    @Shadow private float smoothCamYaw;
    @Shadow private float smoothCamPitch;
    @Shadow private float smoothCamFilterX;
    @Shadow private float smoothCamFilterY;
    @Shadow private float smoothCamPartialTicks;
    @Shadow private double cameraZoom;
    @Shadow private long prevFrameTime;
    @Shadow private long renderEndNanoTime;
    @Shadow private boolean lightmapUpdateNeeded;
    @Shadow public int debugViewDirection;
    @Shadow private long last_vsync_nanotime;
    @Shadow private long fps_start_time;
    @Shadow private int fps_counter;
    @Shadow private long fp10s_start_time;
    @Shadow private int fp10s_counter;
    @Shadow public static boolean anaglyphEnable;
    @Shadow public static int anaglyphField;

    @Shadow private native void updateLightmap(float partial);
    @Shadow private native void updateFogColor(float partial);
    @Shadow private native void setupFog(int mode, float partial);
    @Shadow public native void setupCameraTransform(float partial, int eye, boolean extendFar);
    @Shadow public native void getMouseOver(float partial);
    @Shadow private native void renderHand(float partial, int eye);
    @Shadow protected native void renderRainSnow(float partial);
    @Shadow public native void enableLightmap(double partial);
    @Shadow public native void disableLightmap(double partial);
    @Shadow public native void setupOverlayRendering();
    @Shadow private native void renderCloudsCheck(RenderGlobal rg, float partial);
    @Shadow public static native int performanceToFps(int setting);

    /** Reused culling frustum (render thread only); created on first use, since its constructor reads the GL matrices. */
    private static Frustrum cullingFrustum;
    /** Reused GUI scale; rebuilt only when the display size or the GUI scale setting changes. */
    private static ScaledResolution cachedScaledRes;
    private static int cachedScaledResWidth;
    private static int cachedScaledResHeight;
    private static int cachedScaledResGuiScale;
    /** True once anaglyph has been on, so the eye targets are released when it is turned off. */
    private static boolean anaglyphTargetsLive;

    @Overwrite
    public void updateCameraAndRender(float par1) {
        this.mc.mcProfiler.startSection("lightTex");
        if (this.lightmapUpdateNeeded) {
            this.updateLightmap(par1);
        }

        this.mc.mcProfiler.endSection();
        boolean var2 = Display.isActive();
        if (!var2 && this.mc.gameSettings.pauseOnLostFocus && (!this.mc.gameSettings.touchscreen || !Mouse.isButtonDown(1))) {
            if (Minecraft.getSystemTime() - this.prevFrameTime > 500L) {
                this.mc.displayInGameMenu();
            }
        } else {
            this.prevFrameTime = Minecraft.getSystemTime();
        }

        this.mc.mcProfiler.startSection("mouse");
        if (this.mc.inGameHasFocus && var2) {
            this.mc.mouseHelper.mouseXYChange();
            float var3 = this.mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
            float var4 = var3 * var3 * var3 * 8.0F;
            if (this.mc.thePlayer.zoomed) {
                var4 /= 4.0F;
            }

            float overall_speed_modifier = this.mc.thePlayer.getSpeedBoostVsSlowDown();
            if (overall_speed_modifier < 0.0F) {
                if (overall_speed_modifier < -0.8F) {
                    overall_speed_modifier = -0.8F;
                }

                var4 /= 1.0F - overall_speed_modifier * 15.0F;
            }

            float var5 = this.mc.mouseHelper.deltaX * var4;
            float var6 = this.mc.mouseHelper.deltaY * var4;
            byte var7 = 1;
            if (this.mc.gameSettings.invertMouse) {
                var7 = -1;
            }

            if (this.mc.gameSettings.smoothCamera) {
                this.smoothCamYaw += var5;
                this.smoothCamPitch += var6;
                float var8 = par1 - this.smoothCamPartialTicks;
                this.smoothCamPartialTicks = par1;
                var5 = this.smoothCamFilterX * var8;
                var6 = this.smoothCamFilterY * var8;
                this.mc.thePlayer.setAngles(var5, var6 * var7);
            } else {
                this.mc.thePlayer.setAngles(var5, var6 * var7);
            }
        }

        this.mc.mcProfiler.endSection();
        if (!this.mc.skipRenderWorld) {
            anaglyphEnable = this.mc.gameSettings.anaglyph;
            ScaledResolution var13 = this.cachedScaledResolution();
            int var14 = var13.getScaledWidth();
            int var15 = var13.getScaledHeight();
            int var16 = Mouse.getX() * var14 / this.mc.displayWidth;
            int var17 = var15 - Mouse.getY() * var15 / this.mc.displayHeight - 1;
            int var18 = performanceToFps(this.mc.gameSettings.limitFramerate);
            if (this.mc.theWorld != null && this.mc.theWorld.tick_has_passed) {
                this.mc.mcProfiler.startSection("level");
                if (this.mc.inGameHasFocus || !this.mc.thePlayer.isGhost()) {
                    ShadowCheck.beginFrame();
                    try {
                        if (this.mc.gameSettings.limitFramerate == 0) {
                            this.renderWorld(par1, 0L);
                        } else if (this.mc.gameSettings.limitFramerate == 3) {
                            this.renderWorld(par1, this.renderEndNanoTime + 16666666L);
                        } else {
                            this.renderWorld(par1, this.renderEndNanoTime + 1000000000 / var18);
                        }
                    } finally {
                        ShadowCheck.endFrame();
                    }
                }

                this.renderEndNanoTime = System.nanoTime();
                this.mc.mcProfiler.endStartSection("gui");
                this.mc.ingameGUI.renderGameOverlay(par1, this.mc.isGuiOpen(), var16, var17);
                this.mc.mcProfiler.endSection();
            } else {
                Frame.viewportFull();
                Draw.projection().loadIdentity();
                Draw.pose().loadIdentity();
                this.setupOverlayRendering();
                this.renderEndNanoTime = System.nanoTime();
            }

            if (this.mc.isGuiOpen()) {
                Frame.beginPhase(Phase.GUI);
                Frame.clearDepth();

                try {
                    if (this.mc.currentScreen != null) {
                        this.mc.currentScreen.drawScreen(var16, var17, par1);
                    }

                    if (this.mc.isChatImposed()) {
                        this.mc.imposed_gui_chat.drawScreen(var16, var17, par1);
                    }
                } catch (Throwable var12) {
                    final int sx = var16;
                    final int sy = var17;
                    CrashReport var10 = CrashReport.makeCrashReport(var12, "Rendering screen");
                    CrashReportCategory var11 = var10.makeCategory("Screen render details");
                    var11.addCrashSectionCallable("Screen name", (Callable) () -> this.mc.currentScreen.getClass().getCanonicalName());
                    var11.addCrashSectionCallable("Mouse location", (Callable) () -> String.format("Scaled: (%d, %d). Absolute: (%d, %d)", sx, sy, Mouse.getX(), Mouse.getY()));
                    var11.addCrashSectionCallable("Screen size", (Callable) () -> String.format("Scaled: (%d, %d). Absolute: (%d, %d). Scale factor of %d", var13.getScaledWidth(), var13.getScaledHeight(), this.mc.displayWidth, this.mc.displayHeight, var13.getScaleFactor()));
                    throw new ReportedException(var10);
                }
            }
        }
    }

    /** The scaled resolution for the current display size and GUI scale; rebuilt only when one of them changes. */
    private ScaledResolution cachedScaledResolution() {
        int w = this.mc.displayWidth;
        int h = this.mc.displayHeight;
        int gui = this.mc.gameSettings.guiScale;
        if (cachedScaledRes == null || w != cachedScaledResWidth || h != cachedScaledResHeight || gui != cachedScaledResGuiScale) {
            cachedScaledRes = new ScaledResolution(this.mc.gameSettings, w, h);
            cachedScaledResWidth = w;
            cachedScaledResHeight = h;
            cachedScaledResGuiScale = gui;
        }
        return cachedScaledRes;
    }

    /** The culling frustum, reused across eyes and frames. */
    private static Frustrum cullingFrustum() {
        if (cullingFrustum == null) {
            cullingFrustum = new Frustrum();
        }
        return cullingFrustum;
    }

    /** Anaglyph colour mask of the current eye when the eyes render straight into MAIN (see {@link WorldFrame#usesTargets()}). */
    private static void eyeMask(boolean alpha) {
        if (anaglyphField == 0) {
            Draw.colorMask(false, true, true, alpha);
        } else {
            Draw.colorMask(true, false, false, alpha);
        }
    }

    @Overwrite
    public void renderWorld(float par1, long par2) {
        this.mc.mcProfiler.startSection("lightTex");
        if (this.lightmapUpdateNeeded) {
            this.updateLightmap(par1);
        }

        Draw.cull(true);
        Draw.depthTest(true);
        if (this.mc.renderViewEntity == null) {
            this.mc.renderViewEntity = this.mc.thePlayer;
        }

        this.mc.mcProfiler.endStartSection("pick");
        this.getMouseOver(par1);
        ShadowCheck.onMouseOver(par1);
        EntityLivingBase var4 = this.mc.renderViewEntity;
        if (var4 instanceof EntityPlayer) {
            EntityPlayer entity_player = (EntityPlayer) var4;
            if (entity_player.inBed()) {
                entity_player.setPositionAndRotationInBed();
            }
        }

        RenderGlobal var5 = this.mc.renderGlobal;
        EffectRenderer var6 = this.mc.effectRenderer;
        double var7 = var4.lastTickPosX + (var4.posX - var4.lastTickPosX) * par1;
        double var9 = var4.lastTickPosY + (var4.posY - var4.lastTickPosY) * par1;
        double var11 = var4.lastTickPosZ + (var4.posZ - var4.lastTickPosZ) * par1;
        this.mc.mcProfiler.endStartSection("center");
        boolean anaglyph = this.mc.gameSettings.anaglyph;
        boolean masks = anaglyph && !WorldFrame.usesTargets();
        if (anaglyph) {
            anaglyphTargetsLive = true;
        } else if (anaglyphTargetsLive) {
            WorldFrame.release();
            anaglyphTargetsLive = false;
        }

        for (int var13 = 0; var13 < 2; var13++) {
            if (anaglyph) {
                anaglyphField = var13;
                WorldFrame.beginEye(var13, true);
            }

            try {
                this.mc.mcProfiler.endStartSection("clear");
                Frame.viewportFull();
                this.updateFogColor(par1);
                if (masks) {
                    // MITE clears twice under masks: eye 0 clears G,B and eye 1 clears R, so the colour buffer ends fully cleared.
                    // Frame.clear cannot do a partial colour clear, so eye 0 clears all of RGB (eye 1 then only draws R over it)
                    // and eye 1 clears depth only.
                    if (var13 == 0) {
                        Draw.colorMask(true, true, true, false);
                        Frame.clearColorDepth();
                    } else {
                        Frame.clearDepth();
                    }

                    eyeMask(false);
                } else {
                    Frame.clearColorDepth();
                }

                Draw.cull(true);
                this.mc.mcProfiler.endStartSection("camera");
                this.setupCameraTransform(par1, var13, false);
                if (this.last_vsync_nanotime != -1L) {
                    long milliseconds_since_last_vsync = (System.nanoTime() - this.last_vsync_nanotime) / 1000000L;
                    this.mc.downtimeProcessing(16L - milliseconds_since_last_vsync);
                }

                ActiveRenderInfo.updateRenderInfo(this.mc.thePlayer, this.mc.gameSettings.thirdPersonView == 2);
                this.last_vsync_nanotime = System.nanoTime();
                if (this.last_vsync_nanotime > this.fps_start_time + 1000000000L) {
                    this.fps_start_time = this.last_vsync_nanotime;
                    Minecraft.last_fps = this.fps_counter;
                    this.fps_counter = 0;
                } else {
                    this.fps_counter++;
                }

                if (this.last_vsync_nanotime > this.fp10s_start_time + 10000000000L) {
                    this.fp10s_start_time = this.last_vsync_nanotime;
                    Minecraft.last_fp10s = this.fp10s_counter;
                    this.fp10s_counter = 0;
                } else {
                    this.fp10s_counter++;
                }

                this.mc.mcProfiler.endStartSection("frustrum");
                ClippingHelperImpl.getInstance();
                if (this.mc.gameSettings.getRenderDistance() < 2) {
                    this.setupFog(-1, par1);
                    this.mc.mcProfiler.endStartSection("sky");
                    Frame.beginPhase(Phase.SKY);
                    var5.renderSky(par1);
                }

                Draw.fog(true);
                this.setupFog(1, par1);
                if (this.mc.gameSettings.ambientOcclusion != 0) {
                    Draw.smoothShading(true);
                }

                this.mc.mcProfiler.endStartSection("culling");
                Frustrum var14 = cullingFrustum();
                var14.setPosition(var7, var9, var11);
                // RenderGlobal.clipRenderersByFrustum (native): ring maintenance (render distance change, reposition of the section ring
                // around the viewer) then TerrainRenderer.setupFrame; without the ring step the sections stay at their initial grid cells
                var5.clipRenderersByFrustum(var14, par1);
                if (var13 == 0) {
                    this.mc.mcProfiler.endStartSection("updatechunks");

                    while (!TerrainRenderer.updateChunks(var4, par2, false) && par2 != 0L) {
                        long var15 = par2 - System.nanoTime();
                        if (this.mc.gameSettings.limitFramerate == 3 ? var15 < 1000000L || var15 > 1000000000L : var15 < 0L || var15 > 1000000000L) {
                            break;
                        }
                    }
                }

                if (var4.posY < 128.0) {
                    Frame.beginPhase(Phase.CLOUDS);
                    this.renderCloudsCheck(var5, par1);
                }

                this.mc.mcProfiler.endStartSection("prepareterrain");
                this.setupFog(0, par1);
                Draw.fog(true);
                this.mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
                Lighting.disable();
                this.mc.mcProfiler.endStartSection("terrain");
                Frame.beginPhase(Phase.TERRAIN);
                TerrainRenderer.drawOpaque();
                Draw.smoothShading(false);
                if (this.debugViewDirection == 0) {
                    Lighting.enableStandard();
                    this.mc.mcProfiler.endStartSection("entities");
                    Frame.beginPhase(Phase.ENTITIES);
                    var5.renderEntities(var4.getPosition(par1), var14, par1);
                    this.enableLightmap(par1);
                    this.mc.mcProfiler.endStartSection("litParticles");
                    Frame.beginPhase(Phase.PARTICLES);
                    var6.renderLitParticles(var4, par1);
                    Lighting.disable();
                    this.setupFog(0, par1);
                    this.mc.mcProfiler.endStartSection("particles");
                    var6.renderParticles(var4, par1);
                    this.disableLightmap(par1);
                    if (this.mc.objectMouseOver != null
                        && var4.isInsideOfMaterial(Material.water)
                        && var4 instanceof EntityPlayer
                        && this.mc.gameSettings.gui_mode == 0) {
                        EntityPlayer var17 = (EntityPlayer) var4;
                        Draw.alphaTest(false);
                        this.mc.mcProfiler.endStartSection("outline");
                        Frame.beginPhase(Phase.OVERLAY);
                        var5.drawSelectionBox(var17, this.mc.objectMouseOver, 0, par1);
                        Draw.alphaTest(true);
                    }
                }

                Draw.blend(false);
                Draw.cull(true);
                Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
                Draw.depthMask(true);
                this.setupFog(0, par1);
                Draw.blend(true);
                Draw.cull(false);
                this.mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
                Frame.beginPhase(Phase.TRANSLUCENT);
                this.mc.mcProfiler.endStartSection("water");
                if (this.mc.gameSettings.isFancyGraphicsEnabled() && this.mc.gameSettings.ambientOcclusion != 0) {
                    Draw.smoothShading(true);
                }

                // Fancy: depth pre-pass (colour mask off) then colour pass are handled inside drawTranslucent (design 2.7);
                // the per-eye anaglyph mask (MAIN fallback only) is set before and applies to its colour pass.
                if (masks) {
                    eyeMask(true);
                } else {
                    Draw.colorMask(true, true, true, true);
                }

                TerrainRenderer.drawTranslucent();
                Draw.smoothShading(false);

                Draw.depthMask(true);
                Draw.cull(true);
                Draw.blend(false);
                if (this.cameraZoom == 1.0
                    && var4 instanceof EntityPlayer
                    && this.mc.gameSettings.gui_mode == 0
                    && this.mc.objectMouseOver != null
                    && !var4.isInsideOfMaterial(Material.water)) {
                    EntityPlayer var17 = (EntityPlayer) var4;
                    Draw.alphaTest(false);
                    this.mc.mcProfiler.endStartSection("outline");
                    Frame.beginPhase(Phase.OVERLAY);
                    var5.drawSelectionBox(var17, this.mc.objectMouseOver, 0, par1);
                    Draw.alphaTest(true);
                }

                this.mc.mcProfiler.endStartSection("destroyProgress");
                Frame.beginPhase(Phase.OVERLAY);
                Draw.blend(true);
                Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE);
                var5.drawBlockDamageTexture(Tessellator.instance, (EntityPlayer) var4, par1);
                Draw.blend(false);
                this.mc.mcProfiler.endStartSection("weather");
                this.renderRainSnow(par1);
                Draw.fog(false);
                if (var4.posY >= 128.0) {
                    Frame.beginPhase(Phase.CLOUDS);
                    this.renderCloudsCheck(var5, par1);
                }

                this.mc.mcProfiler.endStartSection("hand");
                if (this.cameraZoom == 1.0) {
                    Frame.beginPhase(Phase.HAND);
                    Frame.clearDepth();
                    this.renderHand(par1, var13);
                }

                if (!anaglyph) {
                    this.mc.mcProfiler.endSection();
                    return;
                }
            } finally {
                // Pops the eye target even when a phase above throws; endEye is a no-op unless the eye target is bound.
                if (anaglyph) {
                    WorldFrame.endEye(var13, true);
                }
            }
        }

        WorldFrame.compositeAnaglyph();
        Draw.colorMask(true, true, true, false);
        this.mc.mcProfiler.endSection();
    }
}
