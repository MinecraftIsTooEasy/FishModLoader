package net.xiaoyu233.fml.vulkan.render.sky;

import net.minecraft.EntityLivingBase;
import net.minecraft.EntityRenderer;
import net.minecraft.MathHelper;
import net.minecraft.Minecraft;
import net.minecraft.RenderGlobal;
import net.minecraft.ResourceLocation;
import net.minecraft.Vec3;
import net.minecraft.WorldClient;
import net.xiaoyu233.fml.vulkan.render.buffer.StaticMeshes;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Face;
import net.xiaoyu233.fml.vulkan.render.tess.NativeTess;
import net.xiaoyu233.fml.vulkan.render.tess.TessBridge;

/**
 * Cloud drawing (3.12), NR-11. Owns the five cloud StaticMeshes ids, the step-wise recompile state (the fields
 * {@code RenderGlobal.last_cloud_compile_x/y/z} stay public and are kept in sync because {@code Minecraft.downtimeProcessing} reads them).
 */
public final class CloudRenderer {
    /** Implemented by the RenderGlobal mixin: access to the private fields the cloud code reads. */
    public interface Access {
        WorldClient nr11$world();

        int nr11$cloudTick();
    }

    private static final ResourceLocation CLOUDS_PNG = new ResourceLocation("textures/environment/clouds.png");
    private static final float EPS = 9.765625E-4F;
    private static final float TS = 0.00390625F;

    private static int baseId;
    private static final boolean[] INITIALIZED = new boolean[5];
    private static int recompilingStep = 0;
    private static boolean lastHasTops;
    private static boolean lastHasBottoms;
    /** {@link StaticMeshes#deviceEpoch()} the cloud meshes were compiled against. */
    private static int builtEpoch = Integer.MIN_VALUE;

    private CloudRenderer() {
    }

    private static int id(int i) {
        if (baseId == 0) {
            baseId = StaticMeshes.reserveIds(5);
        }
        return baseId + i;
    }

    private static WorldClient world(RenderGlobal rg) {
        return ((Access) (Object) rg).nr11$world();
    }

    private static int cloudTick(RenderGlobal rg) {
        return ((Access) (Object) rg).nr11$cloudTick();
    }

    /**
     * Drops the cached cloud meshes so the next frame recompiles (resource reload / world change). Also run automatically from
     * {@link #renderCloudsFancyMite(RenderGlobal, float)} when the {@link StaticMeshes#deviceEpoch()} changed.
     */
    public static void invalidate() {
        if (baseId != 0) {
            for (int i = 0; i < 5; i++) {
                if (INITIALIZED[i]) {
                    StaticMeshes.delete(baseId + i);
                }
                INITIALIZED[i] = false;
            }
        }
        recompilingStep = 0;
        lastHasTops = false;
        lastHasBottoms = false;
    }

    private static boolean canSeeTops(RenderGlobal rg, double viewerY) {
        return world(rg).provider.getCloudHeight() - viewerY + 0.33F <= 0.5;
    }

    private static boolean canSeeBottoms(RenderGlobal rg, double viewerY) {
        return world(rg).provider.getCloudHeight() - viewerY + 0.33F > -4.5;
    }

    /** Body of {@code RenderGlobal.renderClouds} after the {@code isSurfaceWorld()} check (which stays in the mixin for Twilight Forest). */
    public static void renderClouds(RenderGlobal rg, float partialTicks) {
        Minecraft mc = Minecraft.theMinecraft;
        boolean forceFancy = true;
        if (!forceFancy && !mc.gameSettings.isFancyGraphicsEnabled()) {
            renderFlat(rg, mc, partialTicks);
        } else if (mc.gameSettings.anaglyph) {
            renderCloudsFancy(rg, partialTicks);
        } else {
            renderCloudsFancyMite(rg, partialTicks);
        }
    }

    private static void renderFlat(RenderGlobal rg, Minecraft mc, float par1) {
        Draw.cull(true);
        float var2 = (float) (mc.renderViewEntity.lastTickPosY + (mc.renderViewEntity.posY - mc.renderViewEntity.lastTickPosY) * par1);
        byte var3 = 32;
        int var4 = 256 / var3;
        NativeTess t = TessBridge.renderThread();
        mc.getTextureManager().bindTexture(CLOUDS_PNG);
        Draw.blend(true);
        Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
        Vec3 c = world(rg).getCloudColour(par1);
        float r = (float) c.xCoord;
        float g = (float) c.yCoord;
        float b = (float) c.zCoord;
        if (mc.gameSettings.anaglyph) {
            float a = (r * 30.0F + g * 59.0F + b * 11.0F) / 100.0F;
            float d = (r * 30.0F + g * 70.0F) / 100.0F;
            float e = (r * 30.0F + b * 70.0F) / 100.0F;
            r = a;
            g = d;
            b = e;
        }
        float var10 = 4.8828125E-4F;
        double tick = cloudTick(rg) + par1;
        double px = mc.renderViewEntity.prevPosX + (mc.renderViewEntity.posX - mc.renderViewEntity.prevPosX) * par1 + tick * 0.03F;
        double pz = mc.renderViewEntity.prevPosZ + (mc.renderViewEntity.posZ - mc.renderViewEntity.prevPosZ) * par1;
        int ox = MathHelper.floor_double(px / 2048.0);
        int oz = MathHelper.floor_double(pz / 2048.0);
        px -= ox * 2048;
        pz -= oz * 2048;
        float y = world(rg).provider.getCloudHeight() - var2 + 0.33F;
        float u = (float) (px * var10);
        float v = (float) (pz * var10);
        boolean seeBottoms = y > -0.0F;
        Draw.cullFace(seeBottoms ? Face.FRONT : Face.BACK);
        t.startDrawingQuads();
        Draw.color(r, g, b, 0.8F);
        for (int i = -var3 * var4; i < var3 * var4; i += var3) {
            for (int j = -var3 * var4; j < var3 * var4; j += var3) {
                t.addVertexWithUV(i + 0, y, j + var3, (i + 0) * var10 + u, (j + var3) * var10 + v);
                t.addVertexWithUV(i + var3, y, j + var3, (i + var3) * var10 + u, (j + var3) * var10 + v);
                t.addVertexWithUV(i + var3, y, j + 0, (i + var3) * var10 + u, (j + 0) * var10 + v);
                t.addVertexWithUV(i + 0, y, j + 0, (i + 0) * var10 + u, (j + 0) * var10 + v);
            }
        }
        t.draw();
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
        Draw.blend(false);
        Draw.cullFace(Face.BACK);
    }

    /** Body of {@code RenderGlobal.renderCloudsFancy_MITE}. */
    public static void renderCloudsFancyMite(RenderGlobal rg, float par1) {
        Minecraft mc = Minecraft.theMinecraft;
        int epoch = StaticMeshes.deviceEpoch();
        if (epoch != builtEpoch) {
            // device re-created (or first use): the cached meshes are gone, recompile from step 0 below
            invalidate();
            builtEpoch = epoch;
        }
        Draw.smoothShading(false);
        Vec3 rgb = world(rg).getCloudColour(par1);
        float r = (float) rgb.xCoord;
        float g = (float) rgb.yCoord;
        float b = (float) rgb.zCoord;
        float var4 = 12.0F;
        // Inline form of RenderGlobal.getViewerPos(par1, var4): no per-frame double[] on the render thread.
        EntityLivingBase view = mc.renderViewEntity;
        double var8 = (view.prevPosX + (view.posX - view.prevPosX) * par1 + (cloudTick(rg) + par1) * 0.03) / var4;
        float var2 = (float) (view.lastTickPosY + (view.posY - view.lastTickPosY) * par1);
        double var10 = (view.prevPosZ + (view.posZ - view.prevPosZ) * par1) / var4 + 0.33;
        boolean tops = canSeeTops(rg, var2);
        boolean bottoms = canSeeBottoms(rg, var2);
        if (recompilingStep == 0) {
            compileCloudsFancy(rg, par1, var8, var2, var10);
            recompilingStep = -1;
        } else if (Math.abs(rg.last_cloud_compile_x - var8) > 2.0
                || Math.abs(rg.last_cloud_compile_z - var10) > 2.0
                || lastHasTops != tops
                || lastHasBottoms != bottoms) {
            recompilingStep = 0;
        }

        Draw.cull(!(tops && bottoms));
        Draw.fogStart(-16.0F);
        Draw.fogEnd(384.0F);
        mc.entityRenderer.setupCameraTransform(par1, 0, true);
        mc.getTextureManager().bindTexture(CLOUDS_PNG);
        Draw.pushPose();
        Draw.scale(var4, 1.0F, var4);
        Draw.translate(-(var8 - rg.last_cloud_compile_x), -var2, -(var10 - rg.last_cloud_compile_z));
        // list 0: depth only, blend off (recorded state of the display list)
        Draw.blend(false);
        Draw.colorMask(false, false, false, false);
        StaticMeshes.draw(id(0));
        Draw.blend(true);
        Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
        Draw.colorMask(true, true, true, true);
        if (lastHasTops) {
            Draw.color(r, g, b, 0.8F);
            StaticMeshes.draw(id(1));
        }
        if (lastHasBottoms) {
            Draw.color(r * 0.7F, g * 0.7F, b * 0.7F, 0.8F);
            StaticMeshes.draw(id(2));
        }
        Draw.color(r * 0.9F, g * 0.9F, b * 0.9F, 0.8F);
        StaticMeshes.draw(id(3));
        Draw.color(r * 0.8F, g * 0.8F, b * 0.8F, 0.8F);
        StaticMeshes.draw(id(4));
        Draw.blend(false); // each list ends with glDisable(GL_BLEND)
        Draw.popPose();
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.entityRenderer.setupCameraTransform(par1, 0, false);
        Draw.fogStart(64.0F);
        Draw.fogEnd(256.0F);
        Draw.cull(true);
        Draw.smoothShading(true);
    }

    /** Body of {@code RenderGlobal.compileCloudsFancy} (all five meshes). */
    public static void compileCloudsFancy(RenderGlobal rg, float partialTicks, double x, double y, double z) {
        Draw.cull(true);
        Draw.fog(false);
        Draw.alphaTest(false);
        Draw.texturing(false);
        boolean tops = canSeeTops(rg, y);
        boolean bottoms = canSeeBottoms(rg, y);
        rg.last_cloud_compile_x = x;
        rg.last_cloud_compile_y = y;
        rg.last_cloud_compile_z = z;
        lastHasTops = tops;
        lastHasBottoms = bottoms;

        for (recompilingStep = 0; recompilingStep < 5; recompilingStep++) {
            int step = recompilingStep;
            if ((step != 1 || tops) && (step != 2 || bottoms)) {
                if (INITIALIZED[step]) {
                    StaticMeshes.delete(id(step));
                }
                StaticMeshes.beginCapture(id(step));
                try {
                    renderCloudSide(rg, partialTicks, step - 1, rg.last_cloud_compile_x, rg.last_cloud_compile_z, tops, bottoms);
                    StaticMeshes.endCapture();
                } catch (Throwable t) {
                    StaticMeshes.abortCapture();
                    throw t;
                }
                INITIALIZED[step] = true;
            }
        }

        Draw.fog(true);
        Draw.alphaTest(true);
        Draw.texturing(true);
    }

    /** Body of {@code RenderGlobal.renderCloudsFancy} (anaglyph path, streams through NativeTess). */
    public static void renderCloudsFancy(RenderGlobal rg, float par1) {
        Minecraft mc = Minecraft.theMinecraft;
        Draw.cull(false);
        float var2 = (float) (mc.renderViewEntity.lastTickPosY + (mc.renderViewEntity.posY - mc.renderViewEntity.lastTickPosY) * par1);
        NativeTess t = TessBridge.renderThread();
        float var4 = 12.0F;
        float var5 = 4.0F;
        double tick = cloudTick(rg) + par1;
        double var8 = (mc.renderViewEntity.prevPosX + (mc.renderViewEntity.posX - mc.renderViewEntity.prevPosX) * par1 + tick * 0.03F) / var4;
        double var10 = (mc.renderViewEntity.prevPosZ + (mc.renderViewEntity.posZ - mc.renderViewEntity.prevPosZ) * par1) / var4 + 0.33F;
        float var12 = world(rg).provider.getCloudHeight() - var2 + 0.33F;
        int var13 = MathHelper.floor_double(var8 / 2048.0);
        int var14 = MathHelper.floor_double(var10 / 2048.0);
        var8 -= var13 * 2048;
        var10 -= var14 * 2048;
        mc.getTextureManager().bindTexture(CLOUDS_PNG);
        Draw.blend(true);
        Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
        Vec3 c = world(rg).getCloudColour(par1);
        float var16 = (float) c.xCoord;
        float var17 = (float) c.yCoord;
        float var18 = (float) c.zCoord;
        if (mc.gameSettings.anaglyph) {
            float a = (var16 * 30.0F + var17 * 59.0F + var18 * 11.0F) / 100.0F;
            float d = (var16 * 30.0F + var17 * 70.0F) / 100.0F;
            float e = (var16 * 30.0F + var18 * 70.0F) / 100.0F;
            var16 = a;
            var17 = d;
            var18 = e;
        }
        float var21 = TS;
        float var19 = MathHelper.floor_double(var8) * var21;
        float var20 = MathHelper.floor_double(var10) * var21;
        float var22 = (float) (var8 - MathHelper.floor_double(var8));
        float var23 = (float) (var10 - MathHelper.floor_double(var10));
        byte var24 = 8;
        byte var25 = 4;
        float var26 = EPS;
        Draw.scale(var4, 1.0F, var4);

        for (int pass = 0; pass < 2; pass++) {
            if (pass == 0) {
                Draw.colorMask(false, false, false, false);
            } else if (mc.gameSettings.anaglyph) {
                if (EntityRenderer.anaglyphField == 0) {
                    Draw.colorMask(false, true, true, true);
                } else {
                    Draw.colorMask(true, false, false, true);
                }
            } else {
                Draw.colorMask(true, true, true, true);
            }

            for (int i = -var25 + 1; i <= var25; i++) {
                for (int j = -var25 + 1; j <= var25; j++) {
                    t.startDrawingQuads();
                    float var30 = i * var24;
                    float var31 = j * var24;
                    float var32 = var30 - var22;
                    float var33 = var31 - var23;
                    if (var12 > -var5 - 1.0F) {
                        t.setColorRGBA_F(var16 * 0.7F, var17 * 0.7F, var18 * 0.7F, 0.8F);
                        t.setNormal(0.0F, -1.0F, 0.0F);
                        t.addVertexWithUV(var32 + 0.0F, var12 + 0.0F, var33 + var24, (var30 + 0.0F) * var21 + var19, (var31 + var24) * var21 + var20);
                        t.addVertexWithUV(var32 + var24, var12 + 0.0F, var33 + var24, (var30 + var24) * var21 + var19, (var31 + var24) * var21 + var20);
                        t.addVertexWithUV(var32 + var24, var12 + 0.0F, var33 + 0.0F, (var30 + var24) * var21 + var19, (var31 + 0.0F) * var21 + var20);
                        t.addVertexWithUV(var32 + 0.0F, var12 + 0.0F, var33 + 0.0F, (var30 + 0.0F) * var21 + var19, (var31 + 0.0F) * var21 + var20);
                    }
                    if (var12 <= var5 + 1.0F) {
                        t.setColorRGBA_F(var16, var17, var18, 0.8F);
                        t.setNormal(0.0F, 1.0F, 0.0F);
                        t.addVertexWithUV(var32 + 0.0F, var12 + var5 - var26, var33 + var24, (var30 + 0.0F) * var21 + var19, (var31 + var24) * var21 + var20);
                        t.addVertexWithUV(var32 + var24, var12 + var5 - var26, var33 + var24, (var30 + var24) * var21 + var19, (var31 + var24) * var21 + var20);
                        t.addVertexWithUV(var32 + var24, var12 + var5 - var26, var33 + 0.0F, (var30 + var24) * var21 + var19, (var31 + 0.0F) * var21 + var20);
                        t.addVertexWithUV(var32 + 0.0F, var12 + var5 - var26, var33 + 0.0F, (var30 + 0.0F) * var21 + var19, (var31 + 0.0F) * var21 + var20);
                    }
                    t.setColorRGBA_F(var16 * 0.9F, var17 * 0.9F, var18 * 0.9F, 0.8F);
                    if (i > -1) {
                        t.setNormal(-1.0F, 0.0F, 0.0F);
                        for (int k = 0; k < var24; k++) {
                            float ux = (var30 + k + 0.5F) * var21 + var19;
                            t.addVertexWithUV(var32 + k + 0.0F, var12 + 0.0F, var33 + var24, ux, (var31 + var24) * var21 + var20);
                            t.addVertexWithUV(var32 + k + 0.0F, var12 + var5, var33 + var24, ux, (var31 + var24) * var21 + var20);
                            t.addVertexWithUV(var32 + k + 0.0F, var12 + var5, var33 + 0.0F, ux, (var31 + 0.0F) * var21 + var20);
                            t.addVertexWithUV(var32 + k + 0.0F, var12 + 0.0F, var33 + 0.0F, ux, (var31 + 0.0F) * var21 + var20);
                        }
                    }
                    if (i <= 1) {
                        t.setNormal(1.0F, 0.0F, 0.0F);
                        for (int k = 0; k < var24; k++) {
                            float ux = (var30 + k + 0.5F) * var21 + var19;
                            t.addVertexWithUV(var32 + k + 1.0F - var26, var12 + 0.0F, var33 + var24, ux, (var31 + var24) * var21 + var20);
                            t.addVertexWithUV(var32 + k + 1.0F - var26, var12 + var5, var33 + var24, ux, (var31 + var24) * var21 + var20);
                            t.addVertexWithUV(var32 + k + 1.0F - var26, var12 + var5, var33 + 0.0F, ux, (var31 + 0.0F) * var21 + var20);
                            t.addVertexWithUV(var32 + k + 1.0F - var26, var12 + 0.0F, var33 + 0.0F, ux, (var31 + 0.0F) * var21 + var20);
                        }
                    }
                    t.setColorRGBA_F(var16 * 0.8F, var17 * 0.8F, var18 * 0.8F, 0.8F);
                    if (j > -1) {
                        t.setNormal(0.0F, 0.0F, -1.0F);
                        for (int k = 0; k < var24; k++) {
                            float vz = (var31 + k + 0.5F) * var21 + var20;
                            t.addVertexWithUV(var32 + 0.0F, var12 + var5, var33 + k + 0.0F, (var30 + 0.0F) * var21 + var19, vz);
                            t.addVertexWithUV(var32 + var24, var12 + var5, var33 + k + 0.0F, (var30 + var24) * var21 + var19, vz);
                            t.addVertexWithUV(var32 + var24, var12 + 0.0F, var33 + k + 0.0F, (var30 + var24) * var21 + var19, vz);
                            t.addVertexWithUV(var32 + 0.0F, var12 + 0.0F, var33 + k + 0.0F, (var30 + 0.0F) * var21 + var19, vz);
                        }
                    }
                    if (j <= 1) {
                        t.setNormal(0.0F, 0.0F, 1.0F);
                        for (int k = 0; k < var24; k++) {
                            float vz = (var31 + k + 0.5F) * var21 + var20;
                            t.addVertexWithUV(var32 + 0.0F, var12 + var5, var33 + k + 1.0F - var26, (var30 + 0.0F) * var21 + var19, vz);
                            t.addVertexWithUV(var32 + var24, var12 + var5, var33 + k + 1.0F - var26, (var30 + var24) * var21 + var19, vz);
                            t.addVertexWithUV(var32 + var24, var12 + 0.0F, var33 + k + 1.0F - var26, (var30 + var24) * var21 + var19, vz);
                            t.addVertexWithUV(var32 + 0.0F, var12 + 0.0F, var33 + k + 1.0F - var26, (var30 + 0.0F) * var21 + var19, vz);
                        }
                    }
                    t.draw();
                }
            }
        }

        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
        Draw.blend(false);
        Draw.cull(true);
    }

    private static void v(NativeTess t, float x, float y, float z, float u, float v) {
        t.addVertexWithUV(x, y, z, u, v);
    }

    /**
     * Body of {@code RenderGlobal.renderCloudSide}: emits the quads of one cloud list into the open StaticMeshes capture. Vertex order, positions
     * and UVs are those of MITE's raw-buffer scheme (RenderingScheme 1); colour is absent (white), the draw call modulates it.
     */
    public static void renderCloudSide(RenderGlobal rg, float par1, int side, double x, double z, boolean seeTops, boolean seeBottoms) {
        NativeTess t = TessBridge.renderThread();
        float var12 = world(rg).provider.getCloudHeight() - 0.0F + 0.33F;
        double var8 = x;
        double var10 = z;
        int var13 = MathHelper.floor_double(var8 / 2048.0);
        int var14 = MathHelper.floor_double(var10 / 2048.0);
        var8 -= var13 * 2048;
        var10 -= var14 * 2048;
        Minecraft.theMinecraft.getTextureManager().bindTexture(CLOUDS_PNG);
        float var19 = MathHelper.floor_double(var8) * TS;
        float var20 = MathHelper.floor_double(var10) * TS;
        float var22 = (float) (var8 - MathHelper.floor_double(var8));
        float var23 = (float) (var10 - MathHelper.floor_double(var10));
        boolean first = side == -1;

        float top = var12 + 4.0F;
        float bottom = var12;
        float bottomSpaced = seeBottoms && seeTops ? bottom : var12 - 0.01F;

        if (first || side == 0) {
            t.startDrawingQuads();
            float y1 = var12 + 4.0F - EPS;
            for (int i = -5; i <= 5; i++) {
                float var30 = i * 8;
                float u0 = var30 * TS + var19;
                float u1 = (var30 + 8.0F) * TS + var19;
                for (int j = -5; j <= 5; j++) {
                    float var31 = j * 8;
                    float var32 = var30 - var22;
                    float var33 = var31 - var23;
                    float v0 = (var31 + 8.0F) * TS + var20;
                    float v2 = var31 * TS + var20;
                    float x0 = var32;
                    float x1 = var32 + 8.0F;
                    float z0 = var33 + 8.0F;
                    float z2 = var33;
                    v(t, x0, y1, z0, u0, v0);
                    v(t, x1, y1, z0, u1, v0);
                    v(t, x1, y1, z2, u1, v2);
                    v(t, x0, y1, z2, u0, v2);
                }
            }
            t.draw();
        }

        if (first || side == 1) {
            t.startDrawingQuads();
            for (int i = -5; i <= 5; i++) {
                float var30 = i * 8;
                float u0 = var30 * TS + var19;
                float u1 = (var30 + 8.0F) * TS + var19;
                for (int j = -5; j <= 5; j++) {
                    boolean tight = i <= -3 || i >= 3 || j <= -3 || j >= 3;
                    float y0 = tight ? bottom : bottomSpaced;
                    float var31 = j * 8;
                    float var32 = var30 - var22;
                    float var33 = var31 - var23;
                    float v0 = (var31 + 8.0F) * TS + var20;
                    float v2 = var31 * TS + var20;
                    float x0 = var32;
                    float x1 = var32 + 8.0F;
                    float z0 = var33 + 8.0F;
                    float z2 = var33;
                    v(t, x0, y0, z0, u0, v0);
                    v(t, x0, y0, z2, u0, v2);
                    v(t, x1, y0, z2, u1, v2);
                    v(t, x1, y0, z0, u1, v0);
                }
            }
            t.draw();
        }

        if (first || side == 2) {
            t.startDrawingQuads();
            for (int i = -1; i <= 5; i++) {
                float var30 = i * 8;
                for (int j = -5; j <= 5; j++) {
                    float var31 = j * 8;
                    float var32 = var30 - var22;
                    float var33 = var31 - var23;
                    float v0 = (var31 + 8.0F) * TS + var20;
                    float v2 = var31 * TS + var20;
                    float z0 = var33 + 8.0F;
                    float z2 = var33;
                    for (int k = 0; k < 8; k++) {
                        float xp = var32 + k;
                        float u0 = (var30 + k + 0.5F) * TS + var19;
                        v(t, xp, bottom, z0, u0, v0);
                        v(t, xp, top, z0, u0, v0);
                        v(t, xp, top, z2, u0, v2);
                        v(t, xp, bottom, z2, u0, v2);
                    }
                }
            }
            t.draw();
        }

        if (first || side == 2) {
            t.startDrawingQuads();
            for (int i = -5; i < 1; i++) {
                float var30 = i * 8;
                for (int j = -5; j <= 5; j++) {
                    float var31 = j * 8;
                    float var32 = var30 - var22;
                    float var33 = var31 - var23;
                    float v0 = (var31 + 8.0F) * TS + var20;
                    float v2 = var31 * TS + var20;
                    float z0 = var33 + 8.0F;
                    float z2 = var33;
                    for (int k = 0; k < 8; k++) {
                        float xp = var32 + k + 1.0F - EPS;
                        float u0 = (var30 + k + 0.5F) * TS + var19;
                        v(t, xp, bottom, z0, u0, v0);
                        v(t, xp, bottom, z2, u0, v2);
                        v(t, xp, top, z2, u0, v2);
                        v(t, xp, top, z0, u0, v0);
                    }
                }
            }
            t.draw();
        }

        if (first || side == 3) {
            t.startDrawingQuads();
            for (int i = -5; i <= 5; i++) {
                float var30 = i * 8;
                float u0 = var30 * TS + var19;
                float u1 = (var30 + 8.0F) * TS + var19;
                for (int j = -1; j <= 5; j++) {
                    float var31 = j * 8;
                    float var32 = var30 - var22;
                    float var33 = var31 - var23;
                    float x0 = var32;
                    float x1 = var32 + 8.0F;
                    for (int k = 0; k < 8; k++) {
                        float zp = var33 + k;
                        float v0 = (var31 + k + 0.5F) * TS + var20;
                        v(t, x0, top, zp, u0, v0);
                        v(t, x1, top, zp, u1, v0);
                        v(t, x1, bottom, zp, u1, v0);
                        v(t, x0, bottom, zp, u0, v0);
                    }
                }
            }
            t.draw();
        }

        if (first || side == 3) {
            t.startDrawingQuads();
            for (int i = -5; i <= 5; i++) {
                float var30 = i * 8;
                float u0 = var30 * TS + var19;
                float u1 = (var30 + 8.0F) * TS + var19;
                for (int j = -5; j < 1; j++) {
                    float var31 = j * 8;
                    float var32 = var30 - var22;
                    float var33 = var31 - var23;
                    float x0 = var32;
                    float x1 = var32 + 8.0F;
                    for (int k = 0; k < 8; k++) {
                        float zp = var33 + k + 1.0F - EPS;
                        float v0 = (var31 + k + 0.5F) * TS + var20;
                        v(t, x0, top, zp, u0, v0);
                        v(t, x0, bottom, zp, u0, v0);
                        v(t, x1, bottom, zp, u1, v0);
                        v(t, x1, top, zp, u1, v0);
                    }
                }
            }
            t.draw();
        }
    }

    /** Body of {@code EntityRenderer.renderCloudsCheck}. */
    public static void renderCloudsCheck(EntityRenderer er, RenderGlobal rg, float partialTicks) {
    }
}
