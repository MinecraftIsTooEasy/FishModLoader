package net.xiaoyu233.fml.vulkan.render.sky;

import java.util.Random;
import net.minecraft.BiomeGenBase;
import net.minecraft.EntityLivingBase;
import net.minecraft.EntityRenderer;
import net.minecraft.MathHelper;
import net.minecraft.Minecraft;
import net.minecraft.RNG;
import net.minecraft.RenderingScheme;
import net.minecraft.ResourceLocation;
import net.minecraft.Tessellator;
import net.minecraft.Vec3;
import net.minecraft.WorldClient;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;

/** Rain/snow (3.12), NR-11: MITE's CPU code, quads through {@code Tessellator.instance} (NativeTess on the render thread). */
public final class WeatherRenderer {
    private static final ResourceLocation RAIN = new ResourceLocation("textures/environment/rain.png");
    private static final ResourceLocation SNOW = new ResourceLocation("textures/environment/snow.png");

    private static final double[] X = new double[4];
    private static final double[] Y = new double[4];
    private static final double[] Z = new double[4];
    private static final double[] U = new double[4];
    private static final double[] V = new double[4];

    private static boolean chunkLoaded(WorldClient w, int x, int z) {
        return w.getChunkProvider().chunkExists(x >> 4, z >> 4) && !w.getChunkFromChunkCoords(x >> 4, z >> 4).isEmpty();
    }

    private WeatherRenderer() {
    }

    /** Access to the private {@code EntityRenderer} fields, implemented by {@code EntityRendererSkyMixin}. */
    public interface Access {
        float[] fml$rainX();

        float[] fml$rainY();

        void fml$setRain(float[] x, float[] y);

        Random fml$random();

        int fml$updateCount();

        int fml$lastPrecipitationType();

        void fml$setLastPrecipitationType(int t);
    }

    /** Replacement body of {@code EntityRenderer.renderRainSnow(float)}; MITE's CPU code into NativeTess. */
    public static void renderRainSnow(EntityRenderer er, Minecraft mc, float partialTicks) {
        final float par1 = partialTicks;
        Access acc = (Access) (Object) er;
        if (mc.renderViewEntity.ticksExisted < 1) {
            return;
        }
        float var2 = mc.theWorld.getRainStrength(par1);
        if (var2 <= 0.0F) {
            return;
        }
        boolean isBloodMoon = mc.theWorld.isBloodMoon24HourPeriod();
        er.enableLightmap(par1);
        if (acc.fml$rainX() == null) {
            float[] rx = new float[1024];
            float[] ry = new float[1024];
            for (int var3 = 0; var3 < 32; var3++) {
                for (int var4 = 0; var4 < 32; var4++) {
                    float var5 = var4 - 16;
                    float var6 = var3 - 16;
                    float var7 = MathHelper.sqrt_float(var5 * var5 + var6 * var6);
                    rx[var3 << 5 | var4] = -var6 / var7;
                    ry[var3 << 5 | var4] = var5 / var7;
                }
            }
            acc.fml$setRain(rx, ry);
        }
        float[] rainX = acc.fml$rainX();
        float[] rainY = acc.fml$rainY();
        Random random = acc.fml$random();
        int updateCount = acc.fml$updateCount();

        EntityLivingBase var41 = mc.renderViewEntity;
        WorldClient var42 = mc.theWorld;
        int var43 = MathHelper.floor_double(var41.posX);
        int var44 = MathHelper.floor_double(var41.posY);
        int var45 = MathHelper.floor_double(var41.posZ);
        Tessellator var8 = Tessellator.instance;
        Draw.cull(false);
        Draw.normal(0.0F, 1.0F, 0.0F);
        Draw.blend(true);
        Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
        Draw.alphaFunc(0.01F);
        double var9 = var41.lastTickPosX + (var41.posX - var41.lastTickPosX) * par1;
        double var11 = var41.lastTickPosY + (var41.posY - var41.lastTickPosY) * par1;
        double var13 = var41.lastTickPosZ + (var41.posZ - var41.lastTickPosZ) * par1;
        int var15 = MathHelper.floor_double(var11);
        byte var16 = 5;
        if (mc.gameSettings.isFancyGraphicsEnabled()) {
            var16 = 10;
        }
        byte var18 = -1;
        float var19 = updateCount + par1;
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
        int type = mc.theWorld.getPrecipitationType(acc.fml$lastPrecipitationType());
        acc.fml$setLastPrecipitationType(type);

        for (int var20 = var45 - var16; var20 <= var45 + var16; var20++) {
            for (int var21 = var43 - var16; var21 <= var43 + var16; var21++) {
                if ((var21 != var43 || var20 != var45) && chunkLoaded(var42, var21, var20)) {
                    int dx = var21 - var43;
                    int dz = var20 - var45;
                    int indexWrapped = MathHelper.getWrappedIndex(var20 * 32 + var21, 32768);
                    int var22 = (var20 - var45 + 16) * 32 + var21 - var43 + 16;
                    float var23 = rainX[var22] * 0.5F;
                    float var24 = rainY[var22] * 0.5F;
                    BiomeGenBase var25 = var42.getBiomeGenForCoords(var21, var20);
                    if (var25.canSpawnLightningBolt(isBloodMoon) || var25.getEnableSnow()) {
                        int var26 = var42.getPrecipitationHeight(var21, var20);
                        if (var26 < 0) {
                            var26 = 0;
                        }
                        int var27 = var44 - var16;
                        int var28 = var44 + var16;
                        if (var27 < var26) {
                            var27 = var26;
                        }
                        if (var28 < var26) {
                            var28 = var26;
                        }
                        float var29 = 1.0F;
                        int var30 = var26;
                        if (var26 < var15) {
                            var30 = var15;
                        }
                        if (var27 != var28) {
                            double var27Adjusted = var42.getBlockRenderTopY(var21, var27 - 1, var20);
                            random.setSeed(var21 * var21 * 3121 + var21 * 45238971 ^ var20 * var20 * 418711 + var20 * 13761);
                            float var31 = var25.getFloatTemperature();
                            if (var42.getWorldChunkManager().getTemperatureAtHeight(var31, var26) >= 0.15F) {
                                if (var18 != 0) {
                                    if (var18 >= 0) {
                                        var8.draw();
                                    }
                                    var18 = 0;
                                    mc.getTextureManager().bindTexture(RAIN);
                                    var8.startDrawingQuads();
                                }
                                float var32 = ((updateCount + var21 * var21 * 3121 + var21 * 45238971 + var20 * var20 * 418711 + var20 * 13761 & 31) + par1)
                                        / 32.0F * (3.0F + random.nextFloat());
                                double var33 = var21 + 0.5F - var41.posX;
                                double var35 = var20 + 0.5F - var41.posZ;
                                float var37 = MathHelper.sqrt_double(var33 * var33 + var35 * var35) / var16;
                                float var38 = 1.0F;
                                var8.setBrightness(var42.getLightBrightnessForSkyBlocks(var21, var30, var20, 0));
                                var8.setColorRGBA_F(var38, var38, var38, ((1.0F - var37 * var37) * 0.5F + 0.5F) * var2);
                                var8.setTranslation(-var9 * 1.0, -var11 * 1.0, -var13 * 1.0);
                                if (RenderingScheme.current == 0) {
                                    var8.addVertexWithUV(var21 - var23 + 0.5, var27, var20 - var24 + 0.5, 0.0F * var29, var27 * var29 / 4.0F + var32 * var29);
                                    var8.addVertexWithUV(var21 + var23 + 0.5, var27, var20 + var24 + 0.5, 1.0F * var29, var27 * var29 / 4.0F + var32 * var29);
                                    var8.addVertexWithUV(var21 + var23 + 0.5, var28, var20 + var24 + 0.5, 1.0F * var29, var28 * var29 / 4.0F + var32 * var29);
                                    var8.addVertexWithUV(var21 - var23 + 0.5, var28, var20 - var24 + 0.5, 0.0F * var29, var28 * var29 / 4.0F + var32 * var29);
                                } else {
                                    X[0] = var21 - var23 + 0.5F;
                                    Y[0] = var27Adjusted;
                                    Z[0] = var20 - var24 + 0.5F;
                                    U[0] = 0.0;
                                    V[0] = var27Adjusted * var29 / 4.0 + var32 * var29;
                                    X[1] = var21 + var23 + 0.5F;
                                    Y[1] = var27Adjusted;
                                    Z[1] = var20 + var24 + 0.5F;
                                    U[1] = var29;
                                    V[1] = V[0];
                                    X[2] = X[1];
                                    Y[2] = var28;
                                    Z[2] = Z[1];
                                    U[2] = var29;
                                    V[2] = var28 * var29 / 4.0F + var32 * var29;
                                    X[3] = X[0];
                                    Y[3] = var28;
                                    Z[3] = Z[0];
                                    U[3] = 0.0;
                                    V[3] = V[2];
                                    var8.add4VerticesWithUV(X, Y, Z, U, V);
                                }
                                var8.setTranslation(0.0, 0.0, 0.0);
                            } else {
                                if (var18 != 1) {
                                    if (var18 >= 0) {
                                        var8.draw();
                                    }
                                    var18 = 1;
                                    mc.getTextureManager().bindTexture(SNOW);
                                    var8.startDrawingQuads();
                                }
                                float var32 = ((updateCount & 511) + par1) / 512.0F;
                                float verticalOffset;
                                float horizontalOffset;
                                if (type == 0) {
                                    horizontalOffset = RNG.int_32[indexWrapped] / 32.0F;
                                    verticalOffset = var19 * 0.004F;
                                } else if (type == 1) {
                                    horizontalOffset = 1.0F + var19 * RNG.float_1_minus_float_1[indexWrapped] * 0.004F;
                                    verticalOffset = var19 * 0.004F;
                                } else if (type == 2) {
                                    horizontalOffset = 1.0F + var19 * RNG.float_1_minus_float_1[indexWrapped] * 0.008F;
                                    verticalOffset = var19 * 0.004F;
                                } else if (type == 3) {
                                    horizontalOffset = 1.0F + var19 * RNG.float_1_minus_float_1[indexWrapped] * 0.012F;
                                    verticalOffset = var19 * 0.003F;
                                } else {
                                    horizontalOffset = random.nextFloat() + var19 * 0.01F * (float) random.nextGaussian();
                                    verticalOffset = random.nextFloat() + var19 * (float) random.nextGaussian() * 0.001F;
                                }
                                float var46 = horizontalOffset;
                                float var34 = verticalOffset;
                                var34 += RNG.float_1[indexWrapped];
                                double var35 = var21 + 0.5F - var41.posX;
                                double var47 = var20 + 0.5F - var41.posZ;
                                float var39 = MathHelper.sqrt_double(var35 * var35 + var47 * var47) / var16;
                                float var40 = 1.0F;
                                var8.setBrightness((var42.getLightBrightnessForSkyBlocks(var21, var30, var20, 0) * 3 + 15728880) / 4);
                                var8.setColorRGBA_F(var40, var40, var40, MathHelper.clamp_float(0.85F - var39 * 0.35F, 0.0F, 1.0F) * var2);
                                var8.setTranslation(-var9 * 1.0, -var11 * 1.0, -var13 * 1.0);
                                if (RenderingScheme.current == 0) {
                                    var8.addVertexWithUV(var21 - var23 + 0.5, var27, var20 - var24 + 0.5, 0.0F * var29 + var46, var27 * var29 / 4.0F + var32 * var29 + var34);
                                    var8.addVertexWithUV(var21 + var23 + 0.5, var27, var20 + var24 + 0.5, 1.0F * var29 + var46, var27 * var29 / 4.0F + var32 * var29 + var34);
                                    var8.addVertexWithUV(var21 + var23 + 0.5, var28, var20 + var24 + 0.5, 1.0F * var29 + var46, var28 * var29 / 4.0F + var32 * var29 + var34);
                                    var8.addVertexWithUV(var21 - var23 + 0.5, var28, var20 - var24 + 0.5, 0.0F * var29 + var46, var28 * var29 / 4.0F + var32 * var29 + var34);
                                } else {
                                    X[0] = var21 - var23 + 0.5F;
                                    Y[0] = var27Adjusted;
                                    Z[0] = var20 - var24 + 0.5F;
                                    U[0] = var46;
                                    V[0] = var27Adjusted * var29 / 4.0 + var32 * var29 + var34;
                                    X[1] = var21 + var23 + 0.5F;
                                    Y[1] = var27Adjusted;
                                    Z[1] = var20 + var24 + 0.5F;
                                    U[1] = var29 + var46;
                                    V[1] = V[0];
                                    X[2] = X[1];
                                    Y[2] = var28;
                                    Z[2] = Z[1];
                                    U[2] = U[1];
                                    V[2] = var28 * var29 / 4.0F + var32 * var29 + var34;
                                    X[3] = X[0];
                                    Y[3] = var28;
                                    Z[3] = Z[0];
                                    U[3] = var46;
                                    V[3] = V[2];
                                    if (dx * dx + dz * dz <= 9) {
                                        float yaw = (float) MathHelper.getYawInDegrees(var21 + 0.5, var20 + 0.5, var9, var13);
                                        Vec3 rightSide = MathHelper.getNormalizedVector(yaw + 90.0F, 0.0F, var42.getWorldVec3Pool());
                                        X[0] = var21 + 0.5F + rightSide.xCoord / 2.0;
                                        X[1] = var21 + 0.5F - rightSide.xCoord / 2.0;
                                        X[2] = X[1];
                                        X[3] = X[0];
                                        Z[0] = var20 + 0.5F + rightSide.zCoord / 2.0;
                                        Z[1] = var20 + 0.5F - rightSide.zCoord / 2.0;
                                        Z[2] = Z[1];
                                        Z[3] = Z[0];
                                    }
                                    var8.add4VerticesWithUV(X, Y, Z, U, V);
                                }
                                var8.setTranslation(0.0, 0.0, 0.0);
                            }
                        }
                    }
                }
            }
        }

        if (var18 >= 0) {
            var8.draw();
        }
        Draw.cull(true);
        Draw.blend(false);
        Draw.alphaFunc(0.1F);
        er.disableLightmap(par1);
    }
}
