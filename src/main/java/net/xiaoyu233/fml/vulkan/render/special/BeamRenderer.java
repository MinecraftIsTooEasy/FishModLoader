package net.xiaoyu233.fml.vulkan.render.special;

import java.util.Random;
import net.minecraft.MathHelper;
import net.minecraft.RenderHelper;
import net.minecraft.Tessellator;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;

/**
 * Beam/strip helpers of the special renderers (3.7): dragon death beams (triangle fans emitted as triangles),
 * dragon crystal beam, lightning strips. NR-20 owns the implementation; callers are the special mixins.
 */
public final class BeamRenderer {
    /*
     * Scratch state reused across frames. Only the render thread draws entities, so these are never shared between
     * threads. Random is reseeded with setSeed before each use, which yields the same sequence as a fresh Random(seed).
     */
    private static final Random RNG = new Random();
    /** Every element is written before it is read in lightning(), so no clearing is needed between calls. */
    private static final double[] OFF_X = new double[8];
    private static final double[] OFF_Z = new double[8];

    private BeamRenderer() {
    }

    /** MITE RenderDragon.renderDragonDying body: pose is the dragon pose, GL state set up here via Draw. */
    public static void dragonDeathBeams(int deathTicks, float partialTicks) {
        Tessellator tess = Tessellator.instance;
        RenderHelper.disableStandardItemLighting();
        float progress = (deathTicks + partialTicks) / 200.0F;
        float fade = 0.0F;
        if (progress > 0.8F) {
            fade = (progress - 0.8F) / 0.2F;
        }

        Random random = RNG;
        random.setSeed(432L);
        Draw.texturing(false);
        Draw.smoothShading(true);
        Draw.blend(true);
        Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE);
        Draw.alphaTest(false);
        Draw.cull(true);
        Draw.depthMask(false);
        Draw.pushPose();
        Draw.translate(0.0F, -1.0F, -2.0F);

        for (int i = 0; i < (progress + progress * progress) / 2.0F * 60.0F; i++) {
            Draw.rotate(random.nextFloat() * 360.0F, 1.0F, 0.0F, 0.0F);
            Draw.rotate(random.nextFloat() * 360.0F, 0.0F, 1.0F, 0.0F);
            Draw.rotate(random.nextFloat() * 360.0F, 0.0F, 0.0F, 1.0F);
            Draw.rotate(random.nextFloat() * 360.0F, 1.0F, 0.0F, 0.0F);
            Draw.rotate(random.nextFloat() * 360.0F, 0.0F, 1.0F, 0.0F);
            Draw.rotate(random.nextFloat() * 360.0F + progress * 90.0F, 0.0F, 0.0F, 1.0F);
            tess.startDrawing(6); // triangle fan, converted to triangles by NativeTess
            float length = random.nextFloat() * 20.0F + 5.0F + fade * 10.0F;
            float width = random.nextFloat() * 2.0F + 1.0F + fade * 2.0F;
            tess.setColorRGBA_I(16777215, (int) (255.0F * (1.0F - fade)));
            tess.addVertex(0.0, 0.0, 0.0);
            tess.setColorRGBA_I(16711935, 0);
            tess.addVertex(-0.866 * width, length, -0.5F * width);
            tess.addVertex(0.866 * width, length, -0.5F * width);
            tess.addVertex(0.0, length, 1.0F * width);
            tess.addVertex(-0.866 * width, length, -0.5F * width);
            tess.draw();
        }

        Draw.popPose();
        Draw.depthMask(true);
        Draw.cull(false);
        Draw.blend(false);
        Draw.smoothShading(false);
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
        Draw.texturing(true);
        Draw.alphaTest(true);
        RenderHelper.enableStandardItemLighting();
    }

    /**
     * MITE RenderDragon.renderDragon crystal beam (EntityEnderCrystal link), pose set by the caller; (dx, dy, dz) is the
     * vector to the crystal in the rotated frame's source space (var12, var13, var14), the beam texture is bound.
     */
    public static void dragonCrystalBeam(float dx, float dy, float dz, float partialTicks, int ticksExisted) {
        Tessellator tess = Tessellator.instance;
        float length = MathHelper.sqrt_float(dx * dx + dy * dy + dz * dz);
        float v0 = 0.0F - (ticksExisted + partialTicks) * 0.01F;
        float v1 = MathHelper.sqrt_float(dx * dx + dy * dy + dz * dz) / 32.0F - (ticksExisted + partialTicks) * 0.01F;
        tess.startDrawing(5);
        byte segments = 8;

        for (int i = 0; i <= segments; i++) {
            float x = MathHelper.sin(i % segments * (float) Math.PI * 2.0F / segments) * 0.75F;
            float y = MathHelper.cos(i % segments * (float) Math.PI * 2.0F / segments) * 0.75F;
            float u = i % segments * 1.0F / segments;
            tess.setColorOpaque_I(0);
            tess.addVertexWithUV(x * 0.2F, y * 0.2F, 0.0, u, v1);
            tess.setColorOpaque_I(16777215);
            tess.addVertexWithUV(x, y, length, u, v0);
        }

        tess.draw();
    }

    /** MITE RenderLightningBolt.doRenderLightningBolt: additive untextured strips, seeded by boltVertex. */
    public static void lightning(long boltVertex, double x, double y, double z) {
        Tessellator tess = Tessellator.instance;
        Draw.texturing(false);
        Draw.lighting(false);
        Draw.blend(true);
        Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE);
        double[] offX = OFF_X;
        double[] offZ = OFF_Z;
        double accX = 0.0;
        double accZ = 0.0;
        Random seed = RNG;
        seed.setSeed(boltVertex);

        for (int i = 7; i >= 0; i--) {
            offX[i] = accX;
            offZ[i] = accZ;
            accX += seed.nextInt(11) - 5;
            accZ += seed.nextInt(11) - 5;
        }

        for (int pass = 0; pass < 4; pass++) {
            Random random = RNG;
            random.setSeed(boltVertex);

            for (int branch = 0; branch < 3; branch++) {
                int top = 7;
                int bottom = 0;
                if (branch > 0) {
                    top = 7 - branch;
                }

                if (branch > 0) {
                    bottom = top - 2;
                }

                double px = offX[top] - accX;
                double pz = offZ[top] - accZ;

                for (int seg = top; seg >= bottom; seg--) {
                    double prevX = px;
                    double prevZ = pz;
                    if (branch == 0) {
                        px += random.nextInt(11) - 5;
                        pz += random.nextInt(11) - 5;
                    } else {
                        px += random.nextInt(31) - 15;
                        pz += random.nextInt(31) - 15;
                    }

                    tess.startDrawing(5);
                    float brightness = 0.5F;
                    tess.setColorRGBA_F(0.9F * brightness, 0.9F * brightness, 1.0F * brightness, 0.3F);
                    double widthTop = 0.1 + pass * 0.2;
                    if (branch == 0) {
                        widthTop *= seg * 0.1 + 1.0;
                    }

                    double widthBottom = 0.1 + pass * 0.2;
                    if (branch == 0) {
                        widthBottom *= (seg - 1) * 0.1 + 1.0;
                    }

                    for (int corner = 0; corner < 5; corner++) {
                        double ax = x + 0.5 - widthTop;
                        double az = z + 0.5 - widthTop;
                        if (corner == 1 || corner == 2) {
                            ax += widthTop * 2.0;
                        }

                        if (corner == 2 || corner == 3) {
                            az += widthTop * 2.0;
                        }

                        double bx = x + 0.5 - widthBottom;
                        double bz = z + 0.5 - widthBottom;
                        if (corner == 1 || corner == 2) {
                            bx += widthBottom * 2.0;
                        }

                        if (corner == 2 || corner == 3) {
                            bz += widthBottom * 2.0;
                        }

                        tess.addVertex(bx + px, y + seg * 16, bz + pz);
                        tess.addVertex(ax + prevX, y + (seg + 1) * 16, az + prevZ);
                    }

                    tess.draw();
                }
            }
        }

        Draw.blend(false);
        Draw.lighting(true);
        Draw.texturing(true);
    }
}
