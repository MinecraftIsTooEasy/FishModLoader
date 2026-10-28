package net.xiaoyu233.fml.vulkan.render.sky;

import net.minecraft.MathHelper;
import net.minecraft.Minecraft;
import net.minecraft.RenderHelper;
import net.minecraft.ResourceLocation;
import net.minecraft.Tessellator;
import net.minecraft.TextureManager;
import net.minecraft.Vec3;
import net.minecraft.WorldClient;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;

import java.util.Random;

/** Sky drawing (3.12), NR-11: 1:1 port of {@code RenderGlobal.renderSky/renderStars} onto the native Draw/NativeTess APIs. */
final class SkyDraw {
    private static final ResourceLocation MOON_PHASES = new ResourceLocation("textures/environment/moon_phases.png");
    private static final ResourceLocation SUN = new ResourceLocation("textures/environment/sun.png");
    private static final ResourceLocation END_SKY = new ResourceLocation("textures/environment/end_sky.png");
    private static final ResourceLocation MOON_HALO = new ResourceLocation("textures/environment/halo_256.png");
    private static final ResourceLocation MOON_RING = new ResourceLocation("textures/environment/ring_128.png");

    private SkyDraw() {
    }

    static void renderSky(Minecraft mc, TextureManager engine, float par1) {
        WorldClient world = mc.theWorld;
        Draw.lighting(false);
        if (world.provider.dimensionId == 1) {
            Draw.fog(false);
            Draw.alphaTest(false);
            Draw.blend(true);
            Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
            RenderHelper.disableStandardItemLighting();
            Draw.depthMask(false);
            engine.bindTexture(END_SKY);
            Tessellator t = Tessellator.instance;
            for (int i = 0; i < 6; i++) {
                Draw.pushPose();
                if (i == 1) {
                    Draw.rotate(90.0F, 1.0F, 0.0F, 0.0F);
                }
                if (i == 2) {
                    Draw.rotate(-90.0F, 1.0F, 0.0F, 0.0F);
                }
                if (i == 3) {
                    Draw.rotate(180.0F, 1.0F, 0.0F, 0.0F);
                }
                if (i == 4) {
                    Draw.rotate(90.0F, 0.0F, 0.0F, 1.0F);
                }
                if (i == 5) {
                    Draw.rotate(-90.0F, 0.0F, 0.0F, 1.0F);
                }
                t.startDrawingQuads();
                t.setColorOpaque_I(2631720);
                t.addVertexWithUV(-100.0, -100.0, -100.0, 0.0, 0.0);
                t.addVertexWithUV(-100.0, -100.0, 100.0, 0.0, 16.0);
                t.addVertexWithUV(100.0, -100.0, 100.0, 16.0, 16.0);
                t.addVertexWithUV(100.0, -100.0, -100.0, 16.0, 0.0);
                t.draw();
                Draw.popPose();
            }
            Draw.depthMask(true);
            Draw.texturing(true);
            Draw.alphaTest(true);
        } else if (world.provider.isSurfaceWorld()) {
            Draw.texturing(false);
            Vec3 sky = world.getSkyColor(mc.renderViewEntity, par1);
            float r = (float) sky.xCoord;
            float g = (float) sky.yCoord;
            float b = (float) sky.zCoord;
            if (mc.gameSettings.anaglyph) {
                float r2 = (r * 30.0F + g * 59.0F + b * 11.0F) / 100.0F;
                float g2 = (r * 30.0F + g * 70.0F) / 100.0F;
                float b2 = (r * 30.0F + b * 70.0F) / 100.0F;
                r = r2;
                g = g2;
                b = b2;
            }
            Draw.color(r, g, b);
            Tessellator t = Tessellator.instance;
            Draw.depthMask(false);
            Draw.fog(true);
            Draw.color(r, g, b);
            SkyRenderer.drawList(1);
            Draw.fog(false);
            Draw.alphaTest(false);
            Draw.blend(true);
            Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
            RenderHelper.disableStandardItemLighting();
            float[] sunrise = world.provider.calcSunriseSunsetColors(world.getCelestialAngle(par1), par1);
            if (sunrise != null) {
                Draw.texturing(false);
                Draw.smoothShading(true);
                Draw.pushPose();
                Draw.rotate(90.0F, 1.0F, 0.0F, 0.0F);
                Draw.rotate(MathHelper.sin(world.getCelestialAngleRadians(par1)) < 0.0F ? 180.0F : 0.0F, 0.0F, 0.0F, 1.0F);
                Draw.rotate(90.0F, 0.0F, 0.0F, 1.0F);
                float sr = sunrise[0];
                float sg = sunrise[1];
                float sb = sunrise[2];
                if (mc.gameSettings.anaglyph) {
                    float r2 = (sr * 30.0F + sg * 59.0F + sb * 11.0F) / 100.0F;
                    float g2 = (sr * 30.0F + sg * 70.0F) / 100.0F;
                    float b2 = (sr * 30.0F + sb * 70.0F) / 100.0F;
                    sr = r2;
                    sg = g2;
                    sb = b2;
                }
                // GL_TRIANGLE_FAN (6): NativeTess converts the fan to triangles
                t.startDrawing(6);
                t.setColorRGBA_F(sr, sg, sb, sunrise[3]);
                t.addVertex(0.0, 100.0, 0.0);
                byte segments = 16;
                t.setColorRGBA_F(sunrise[0], sunrise[1], sunrise[2], 0.0F);
                for (int i = 0; i <= segments; i++) {
                    float a = i * (float) Math.PI * 2.0F / segments;
                    float s = MathHelper.sin(a);
                    float c = MathHelper.cos(a);
                    t.addVertex(s * 120.0F, c * 120.0F, -c * 40.0F * sunrise[3]);
                }
                t.draw();
                Draw.popPose();
                Draw.smoothShading(false);
            }

            Draw.texturing(true);
            Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE);
            Draw.pushPose();
            float rain = 1.0F - world.getRainStrength(par1);
            Draw.color(1.0F, 1.0F, 1.0F, rain);
            Draw.translate(0.0F, 0.0F, 0.0F);
            Draw.rotate(-90.0F, 0.0F, 1.0F, 0.0F);
            Draw.rotate(world.getCelestialAngle(par1) * 360.0F, 1.0F, 0.0F, 0.0F);
            float size = 30.0F;
            engine.bindTexture(SUN);
            t.startDrawingQuads();
            t.addVertexWithUV(-size, 100.0, -size, 0.0, 0.0);
            t.addVertexWithUV(size, 100.0, -size, 1.0, 0.0);
            t.addVertexWithUV(size, 100.0, size, 1.0, 1.0);
            t.addVertexWithUV(-size, 100.0, size, 0.0, 1.0);
            t.draw();
            if (world.isBloodMoon(false)) {
                Draw.color(0.6F, 0.2F, 0.1F, rain);
            } else if (world.isHarvestMoon24HourPeriod()) {
                Draw.color(1.0F, 0.8F, 0.45F, rain);
            } else if (world.isBlueMoon(false)) {
                Draw.color(0.66F, 0.74F, 1.0F, rain);
            } else {
                Draw.color(1.0F, 1.0F, 1.0F, rain);
            }

            size = 20.0F;
            engine.bindTexture(MOON_PHASES);
            int phase = world.getMoonPhase();
            int px = phase % 4;
            int py = phase / 4 % 2;
            float u0 = (px + 0) / 4.0F;
            float v0 = (py + 0) / 2.0F;
            float u1 = (px + 1) / 4.0F;
            float v1 = (py + 1) / 2.0F;
            t.startDrawingQuads();
            t.addVertexWithUV(-size, -100.0, size, u1, v1);
            t.addVertexWithUV(size, -100.0, size, u0, v1);
            t.addVertexWithUV(size, -100.0, -size, u0, v0);
            t.addVertexWithUV(-size, -100.0, -size, u1, v0);
            t.draw();
            if (!world.isBloodMoon24HourPeriod() && !world.isBlueMoon24HourPeriod()) {
                if (world.isMoonDog24HourPeriod()) {
                    float alpha = (1.0F - world.getRainStrength(par1)) * 0.5F;
                    float startIn = 0.18F;
                    float stopIn = 0.28F;
                    float startOut = 0.6F;
                    float stopOut = 0.785F;
                    float angle = world.getCelestialAngle(par1);
                    if (angle < startIn || angle > stopOut) {
                        alpha = 0.0F;
                    } else if (angle < stopIn) {
                        alpha *= (angle - startIn) / (stopIn - startIn);
                    } else if (angle > startOut) {
                        alpha *= 1.0F - (angle - startOut) / (stopOut - startOut);
                    }

                    if (alpha > 0.0F) {
                        Draw.color(0.4975F, 0.6275F, 0.85F, alpha);
                        engine.bindTexture(MOON_RING);
                        float ring = 80.0F;
                        t.startDrawingQuads();
                        t.addVertexWithUV(-ring, -100.0, ring, 0.0, 0.0);
                        t.addVertexWithUV(ring, -100.0, ring, 1.0, 0.0);
                        t.addVertexWithUV(ring, -100.0, -ring, 1.0, 1.0);
                        t.addVertexWithUV(-ring, -100.0, -ring, 0.0, 1.0);
                        t.draw();
                    }
                }
            } else {
                float alpha = 0.25F;
                float angle = world.getCelestialAngle(par1);
                float startFading = 0.77F;
                if (angle > startFading) {
                    alpha *= 1.0F - (angle - startFading) / (0.785F - startFading);
                }

                if (world.isBloodMoon24HourPeriod()) {
                    Draw.color(0.6F, 0.2F, 0.1F, rain * alpha);
                } else {
                    Draw.color(0.33F, 0.37F, 0.8F, rain * alpha);
                }

                engine.bindTexture(MOON_HALO);
                float halo = 160.0F;
                t.startDrawingQuads();
                t.addVertexWithUV(-halo, -100.0, halo, 0.0, 0.0);
                t.addVertexWithUV(halo, -100.0, halo, 1.0, 0.0);
                t.addVertexWithUV(halo, -100.0, -halo, 1.0, 1.0);
                t.addVertexWithUV(-halo, -100.0, -halo, 0.0, 1.0);
                t.draw();
            }

            Draw.color(1.0F, 1.0F, 1.0F, rain);
            Draw.texturing(false);
            float star = world.getStarBrightness(par1) * rain;
            if (star > 0.0F) {
                Draw.color(star, star, star, star);
                SkyRenderer.drawList(0);
            }

            Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
            Draw.blend(false);
            Draw.alphaTest(true);
            Draw.fog(true);
            Draw.popPose();
            Draw.texturing(false);
            Draw.color(0.0F, 0.0F, 0.0F);
            double horizon = mc.thePlayer.getPosition(par1).yCoord - world.getHorizon();
            if (horizon < 0.0) {
                Draw.pushPose();
                Draw.translate(0.0F, 12.0F, 0.0F);
                SkyRenderer.drawList(2);
                Draw.popPose();
                float a = 1.0F;
                float b0 = -((float) (horizon + 65.0));
                float c = -a;
                t.startDrawingQuads();
                t.setColorRGBA_I(0, 255);
                t.addVertex(-a, b0, a);
                t.addVertex(a, b0, a);
                t.addVertex(a, c, a);
                t.addVertex(-a, c, a);
                t.addVertex(-a, c, -a);
                t.addVertex(a, c, -a);
                t.addVertex(a, b0, -a);
                t.addVertex(-a, b0, -a);
                t.addVertex(a, c, -a);
                t.addVertex(a, c, a);
                t.addVertex(a, b0, a);
                t.addVertex(a, b0, -a);
                t.addVertex(-a, b0, -a);
                t.addVertex(-a, b0, a);
                t.addVertex(-a, c, a);
                t.addVertex(-a, c, -a);
                t.addVertex(-a, c, -a);
                t.addVertex(-a, c, a);
                t.addVertex(a, c, a);
                t.addVertex(a, c, -a);
                t.draw();
            }

            if (world.provider.isSkyColored()) {
                Draw.color(r * 0.2F + 0.04F, g * 0.2F + 0.04F, b * 0.6F + 0.1F);
            } else {
                Draw.color(r, g, b);
            }

            Draw.pushPose();
            Draw.translate(0.0F, -((float) (horizon - 16.0)), 0.0F);
            SkyRenderer.drawList(2);
            Draw.popPose();
            Draw.texturing(true);
            Draw.depthMask(true);
        }
    }

    /** Emits the star quads into the current capture (MITE {@code renderStars}). */
    static void renderStars() {
        Random rnd = new Random(10842L);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();

        for (int n = 0; n < 1500; n++) {
            double x = rnd.nextFloat() * 2.0F - 1.0F;
            double y = rnd.nextFloat() * 2.0F - 1.0F;
            double z = rnd.nextFloat() * 2.0F - 1.0F;
            double size = 0.15F + rnd.nextFloat() * 0.1F;
            double len = x * x + y * y + z * z;
            if (len < 1.0 && len > 0.01) {
                len = 1.0 / Math.sqrt(len);
                x *= len;
                y *= len;
                z *= len;
                double px = x * 100.0;
                double py = y * 100.0;
                double pz = z * 100.0;
                double yaw = Math.atan2(x, z);
                double sinYaw = Math.sin(yaw);
                double cosYaw = Math.cos(yaw);
                double pitch = Math.atan2(Math.sqrt(x * x + z * z), y);
                double sinPitch = Math.sin(pitch);
                double cosPitch = Math.cos(pitch);
                double roll = rnd.nextDouble() * Math.PI * 2.0;
                double sinRoll = Math.sin(roll);
                double cosRoll = Math.cos(roll);

                for (int k = 0; k < 4; k++) {
                    double ox = 0.0;
                    double a = ((k & 2) - 1) * size;
                    double b = ((k + 1 & 2) - 1) * size;
                    double rx = a * cosRoll - b * sinRoll;
                    double ry = b * cosRoll + a * sinRoll;
                    double ty = rx * sinPitch + ox * cosPitch;
                    double tz = ox * sinPitch - rx * cosPitch;
                    double fx = tz * sinYaw - ry * cosYaw;
                    double fz = ry * sinYaw + tz * cosYaw;
                    t.addVertex(px + fx, py + ty, pz + fz);
                }
            }
        }

        t.draw();
    }

    /** MITE {@code RenderGlobal.<init>} sky disc list (glSkyList): one quad batch per 64-step cell at y = +16. */
    static void buildSkyDisc() {
        Tessellator t = Tessellator.instance;
        byte step = 64;
        int n = 256 / step + 2;
        float y = 16.0F;
        for (int x = -step * n; x <= step * n; x += step) {
            for (int z = -step * n; z <= step * n; z += step) {
                t.startDrawingQuads();
                t.addVertex(x + 0, y, z + 0);
                t.addVertex(x + step, y, z + 0);
                t.addVertex(x + step, y, z + step);
                t.addVertex(x + 0, y, z + step);
                t.draw();
            }
        }
    }

    /** MITE {@code RenderGlobal.<init>} lower disc list (glSkyList2): single quad batch at y = -16. */
    static void buildLowerDisc() {
        Tessellator t = Tessellator.instance;
        byte step = 64;
        int n = 256 / step + 2;
        float y = -16.0F;
        t.startDrawingQuads();
        for (int x = -step * n; x <= step * n; x += step) {
            for (int z = -step * n; z <= step * n; z += step) {
                t.addVertex(x + step, y, z + 0);
                t.addVertex(x + 0, y, z + 0);
                t.addVertex(x + 0, y, z + step);
                t.addVertex(x + step, y, z + step);
            }
        }
        t.draw();
    }
}
