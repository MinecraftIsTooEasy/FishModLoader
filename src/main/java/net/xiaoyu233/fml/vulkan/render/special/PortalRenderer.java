package net.xiaoyu233.fml.vulkan.render.special;

import java.util.Random;
import net.minecraft.ActiveRenderInfo;
import net.minecraft.Minecraft;
import net.minecraft.ResourceLocation;
import net.minecraft.TileEntityRenderer;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.core.StreamSlice;
import net.xiaoyu233.fml.vulkan.render.buffer.Batches;
import net.xiaoyu233.fml.vulkan.render.buffer.QuadIndexBuffers;
import net.xiaoyu233.fml.vulkan.render.gpu.CommandStream;
import net.xiaoyu233.fml.vulkan.render.gpu.DeviceFeatures;
import net.xiaoyu233.fml.vulkan.render.gpu.PassUniforms;
import net.xiaoyu233.fml.vulkan.render.gpu.PipelineLibrary;
import net.xiaoyu233.fml.vulkan.render.gpu.Program;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.MaterialKey;
import net.xiaoyu233.fml.vulkan.render.state.PoseStack;
import net.xiaoyu233.fml.vulkan.render.state.RenderType;
import net.xiaoyu233.fml.vulkan.render.state.VertexFormat;
import net.xiaoyu233.fml.vulkan.render.texture.Textures;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

/**
 * End portal drawing (3.7), replacing {@code RenderEndPortal.renderEndPortalTileEntity}: the 16 parallax layers with MITE's colours,
 * blend, scale and translation. MITE's {@code glTexGen} planes (3168-3171, handled only here) and the per-layer texture matrix are
 * evaluated on the CPU and pushed per layer.
 *
 * <p>Push constant layout of the PORTAL program (128 B, VERTEX + FRAGMENT stages), as declared in end_portal.vert:
 * <pre>
 *   0..63   mat4 texgen  (column major, m[col*4+row] = plane[row][col]): gen = texgen * vec4(objectPos, 1) = (S, T, R, Q).
 *                        S = x, T = z, R = 1 (object-linear planes 1000, 0010, 0001); Q = y - translateY (row 3 = (0, 1, 0, -ty)),
 *                        i.e. MITE's eye-linear Q plane folded with the pose on the CPU.
 *   64..127 mat4 uv      the layer's GL texture matrix (translate/scale/rotate of MITE), column major; texcoord = uv * gen.
 * </pre>
 * The fragment shader performs the projective lookup {@code textureProj(uTexture, (s, t, q))} with (s, t, q) taken from x, y, w of
 * the texcoord, multiplies by the vertex colour and applies fog. Vertex input (VertexFormat.PORTAL, 28 B): eye position f32x3
 * (pose * vertex), object position f32x3, colour RGBA8.
 *
 * <p>Each layer is recorded directly (after flushing the pending batches so the order is kept) because the batching layer has no
 * per-entry push constants.
 */
public final class PortalRenderer {
    private static final ResourceLocation SKY = new ResourceLocation("textures/environment/end_sky.png");
    private static final ResourceLocation PORTAL = new ResourceLocation("textures/entity/end_portal.png");
    private static final Random RANDOM = new Random(31100L);
    private static final PoseStack TEX = new PoseStack(2);
    private static final float[] POSE_COPY = new float[16];
    private static long pc;

    private PortalRenderer() {
    }

    public static void draw(double x, double y, double z, float partialTicks, TileEntityRenderer dispatcher) {
        float playerX = (float) dispatcher.playerX;
        float playerY = (float) dispatcher.playerY;
        float playerZ = (float) dispatcher.playerZ;
        Draw.lighting(false);
        RANDOM.setSeed(31100L);
        float height = 0.75F;
        float time = (float) (Minecraft.getSystemTime() % 700000L) / 700000.0F;

        for (int layer = 0; layer < 16; layer++) {
            float depth = 16 - layer;
            float scale = 0.0625F;
            float intensity = 1.0F / (depth + 1.0F);
            if (layer == 0) {
                dispatcher.renderEngine.bindTexture(SKY);
                intensity = 0.1F;
                depth = 65.0F;
                scale = 0.125F;
                Draw.blend(true);
                Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
            }
            if (layer == 1) {
                dispatcher.renderEngine.bindTexture(PORTAL);
                Draw.blend(true);
                Draw.blendFunc(BlendFactor.ONE, BlendFactor.ONE);
                scale = 0.5F;
            }

            float negY = (float) (-(y + height));
            float a = negY + ActiveRenderInfo.objectY;
            float b = negY + depth + ActiveRenderInfo.objectY;
            float ty = a / b;
            ty = (float) (y + height) + ty;

            // texture matrix (MITE GL_TEXTURE stack, evaluated here)
            TEX.loadIdentity();
            TEX.translate(0.0F, time, 0.0F);
            TEX.scale(scale, scale, scale);
            TEX.translate(0.5F, 0.5F, 0.0F);
            TEX.rotate((layer * layer * 4321 + layer * 9) * 2.0F, 0.0F, 0.0F, 1.0F);
            TEX.translate(-0.5F, -0.5F, 0.0F);
            TEX.translate(-playerX, -playerZ, -playerY);
            a = negY + ActiveRenderInfo.objectY;
            TEX.translate(ActiveRenderInfo.objectX * depth / a, ActiveRenderInfo.objectZ * depth / a, -playerY);
            float[] t = TEX.top();

            float r = RANDOM.nextFloat() * 0.5F + 0.1F;
            float g = RANDOM.nextFloat() * 0.5F + 0.4F;
            float bl = RANDOM.nextFloat() * 0.5F + 0.5F;
            if (layer == 0) {
                r = g = bl = 1.0F;
            }
            emit(layer, x, y + height, z, r * intensity, g * intensity, bl * intensity, ty, t);
        }
        Draw.blend(false);
        Draw.lighting(true);
    }

    /** Same truncation as {@code Tessellator.setColorRGBA_F} (int cast of c * 255), clamped to 0..255 as {@code setColorRGBA} does. */
    private static int pack(float r, float g, float b) {
        int ri = Math.min(255, Math.max(0, (int) (r * 255.0F)));
        int gi = Math.min(255, Math.max(0, (int) (g * 255.0F)));
        int bi = Math.min(255, Math.max(0, (int) (b * 255.0F)));
        return ri | gi << 8 | bi << 16 | 0xFF000000;
    }

    private static void emit(int layer, double x, double y, double z, float r, float g, float b, float ty, float[] tex) {
        RenderType type = Draw.currentRenderType(VertexFormat.PORTAL);
        Batches.consumer(type); // synchronises the pass uniforms (projection, fog) and flushes on changes
        if (Batches.isTestCapturing()) {
            return;
        }
        Batches.flushPhase(); // keep the order against everything queued before this layer
        long key = type.materialKey();
        Program program = Program.ofOrdinal(MaterialKey.program(key));
        if (program == null) {
            return;
        }
        boolean dyn = DeviceFeatures.extendedDynamicState();
        long pipeline = PipelineLibrary.get(program, MaterialKey.pipelineKey(key, dyn), VertexFormat.PORTAL);
        if (pipeline == 0L) {
            return;
        }
        CommandStream.bindPipeline(pipeline);
        CommandStream.bindUniforms(PassUniforms.descriptorSet(), PassUniforms.currentOffset());
        long dummy = Textures.descriptorSet(0);
        long texSet = type.texture() == 0 ? dummy : Textures.descriptorSet(type.texture());
        CommandStream.bindTexture(0, texSet != 0L ? texSet : dummy);
        CommandStream.bindTexture(1, dummy);
        if (dyn) {
            boolean test = MaterialKey.depthTest(key);
            int cull = MaterialKey.cullFront(key) ? VK10.VK_CULL_MODE_FRONT_BIT
                    : MaterialKey.cullBack(key) ? VK10.VK_CULL_MODE_BACK_BIT : VK10.VK_CULL_MODE_NONE;
            CommandStream.setDynamicDepthCull(test, test && MaterialKey.depthWrite(key),
                    test ? MaterialKey.depthFunc(key) : VK10.VK_COMPARE_OP_ALWAYS, cull);
        }

        // push constants
        if (pc == 0L) {
            pc = MemoryUtil.nmemCalloc(1, 128);
        }
        long p = pc;
        for (int i = 0; i < 16; i++) {
            MemoryUtil.memPutFloat(p + 4L * i, 0.0F);
        }
        MemoryUtil.memPutFloat(p, 1.0F);          // S = x   (column 0, row 0)
        MemoryUtil.memPutFloat(p + 4L * 2, 0.0F);
        MemoryUtil.memPutFloat(p + 4L * (2 * 4 + 1 - 1), 0.0F);
        // row 1 (T = z): element (row 1, col 2) at col*4+row = 9
        MemoryUtil.memPutFloat(p + 4L * 9, 1.0F);
        // row 2 (R = 1): element (row 2, col 3) at 14
        MemoryUtil.memPutFloat(p + 4L * 14, 1.0F);
        // row 3 (Q = y - ty): (row 3, col 1) at 7, (row 3, col 3) at 15
        MemoryUtil.memPutFloat(p + 4L * 7, 1.0F);
        MemoryUtil.memPutFloat(p + 4L * 15, -ty);
        for (int i = 0; i < 16; i++) {
            MemoryUtil.memPutFloat(p + 64L + 4L * i, tex[i]);
        }
        CommandStream.pushConstants(p, 0, 128);

        // vertices: eye = pose * object
        Draw.pose().copyTop(POSE_COPY, 0);
        StreamSlice slice = RenderCore.streamVertex(4 * 28);
        long v = slice.address();
        int color = pack(r, g, b);
        float[] m = POSE_COPY;
        for (int i = 0; i < 4; i++) {
            // corners in order (x, y, z), (x, y, z + 1), (x + 1, y, z + 1), (x + 1, y, z)
            float ox = (float) (x + ((i >> 1) & 1));
            float oy = (float) y;
            float oz = (float) (z + (((i + 1) >> 1) & 1));
            long o = v + 28L * i;
            MemoryUtil.memPutFloat(o, m[0] * ox + m[4] * oy + m[8] * oz + m[12]);
            MemoryUtil.memPutFloat(o + 4, m[1] * ox + m[5] * oy + m[9] * oz + m[13]);
            MemoryUtil.memPutFloat(o + 8, m[2] * ox + m[6] * oy + m[10] * oz + m[14]);
            MemoryUtil.memPutFloat(o + 12, ox);
            MemoryUtil.memPutFloat(o + 16, oy);
            MemoryUtil.memPutFloat(o + 20, oz);
            MemoryUtil.memPutInt(o + 24, color);
        }
        CommandStream.bindVertexBuffer(0, slice.buffer(), slice.offset());
        CommandStream.bindIndexBuffer(QuadIndexBuffers.u16Buffer(), QuadIndexBuffers.u16Offset(), false);
        CommandStream.drawIndexed(6, 0, 0);
    }
}
