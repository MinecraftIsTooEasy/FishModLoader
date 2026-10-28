package net.xiaoyu233.fml.vulkan.render.world;

import net.minecraft.ActiveRenderInfo;
import net.minecraft.ClippingHelper;
import net.minecraft.MathHelper;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Frame;

/**
 * Camera/projection math of the world frame (NR-12, owner of render/world). Pure float math, no Vulkan, no allocation per call.
 * Matrices are GL column-major float[16] exactly as {@code PoseStack.top()} holds them; results must equal the phase-1 / LWJGL 2
 * values bit for bit (frozen-frame equality test of NR-12).
 */
public final class CameraMatrices {
    private CameraMatrices() {
    }

    private static final float[] FINAL = new float[16];
    private static final float[] TEMP = new float[16];
    private static final float[] IN = new float[4];
    private static final float[] OUT = new float[4];
    private static final float[] OBJ = new float[3];
    private static final int[] VIEWPORT = new int[4];
    private static final java.nio.IntBuffer VIEWPORT_BUF = java.nio.IntBuffer.allocate(4);

    private static void identity(float[] m) {
        for (int i = 0; i < 16; i++) {
            m[i] = (i % 5 == 0) ? 1.0F : 0.0F;
        }
    }

    /** LWJGL2 __gluInvertMatrixf with src == inverse == FINAL (as gluUnProject calls it). */
    private static boolean invertFinal() {
        final float[] inverse = FINAL;
        final float[] temp = TEMP;
        int i, j, k, swap;
        float t;
        System.arraycopy(FINAL, 0, temp, 0, 16);
        identity(inverse);
        for (i = 0; i < 4; i++) {
            swap = i;
            for (j = i + 1; j < 4; j++) {
                if (Math.abs(temp[j * 4 + i]) > Math.abs(temp[i * 4 + i])) {
                    swap = j;
                }
            }
            if (swap != i) {
                for (k = 0; k < 4; k++) {
                    t = temp[i * 4 + k];
                    temp[i * 4 + k] = temp[swap * 4 + k];
                    temp[swap * 4 + k] = t;
                    t = inverse[i * 4 + k];
                    inverse[i * 4 + k] = inverse[swap * 4 + k];
                    inverse[swap * 4 + k] = t;
                }
            }
            if (temp[i * 4 + i] == 0) {
                return false;
            }
            t = temp[i * 4 + i];
            for (k = 0; k < 4; k++) {
                temp[i * 4 + k] = temp[i * 4 + k] / t;
                inverse[i * 4 + k] = inverse[i * 4 + k] / t;
            }
            for (j = 0; j < 4; j++) {
                if (j != i) {
                    t = temp[j * 4 + i];
                    for (k = 0; k < 4; k++) {
                        temp[j * 4 + k] = temp[j * 4 + k] - temp[i * 4 + k] * t;
                        inverse[j * 4 + k] = inverse[j * 4 + k] - inverse[i * 4 + k] * t;
                    }
                }
            }
        }
        return true;
    }

    /** Port of {@code org.lwjgl.util.glu.Project.gluUnProject} (float arithmetic, same inversion order). Writes x, y, z to out[0..2]; false when singular. */
    public static boolean unProject(float winX, float winY, float winZ, float[] modelview, float[] projection, int[] viewport, float[] out) {
        final float[] a = modelview;
        final float[] b = projection;
        final float[] r = FINAL;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                r[i * 4 + j] = a[i * 4 + 0] * b[0 * 4 + j] + a[i * 4 + 1] * b[1 * 4 + j] + a[i * 4 + 2] * b[2 * 4 + j] + a[i * 4 + 3] * b[3 * 4 + j];
            }
        }
        if (!invertFinal()) {
            return false;
        }
        final float[] in = IN;
        final float[] o = OUT;
        in[0] = winX;
        in[1] = winY;
        in[2] = winZ;
        in[3] = 1.0f;
        in[0] = (in[0] - viewport[0]) / viewport[2];
        in[1] = (in[1] - viewport[1]) / viewport[3];
        in[0] = in[0] * 2 - 1;
        in[1] = in[1] * 2 - 1;
        in[2] = in[2] * 2 - 1;
        for (int i = 0; i < 4; i++) {
            o[i] = in[0] * r[i] + in[1] * r[4 + i] + in[2] * r[2 * 4 + i] + in[3] * r[3 * 4 + i];
        }
        if (o[3] == 0.0) {
            return false;
        }
        o[3] = 1.0f / o[3];
        out[0] = o[0] * o[3];
        out[1] = o[1] * o[3];
        out[2] = o[2] * o[3];
        return true;
    }

    private static void normalize(float[][] f, int n) {
        float[] p = f[n];
        float len = MathHelper.sqrt_float(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        p[0] = p[0] / len;
        p[1] = p[1] / len;
        p[2] = p[2] / len;
        p[3] = p[3] / len;
    }

    /**
     * Port of {@code ClippingHelperImpl.init} arithmetic: fills {@code projectionMatrix}, {@code modelviewMatrix}, {@code clippingMatrix},
     * {@code frustum[0..5]} (normalised with MathHelper.sqrt_float) and the 24 {@code frustum_i_j} fields of {@code h} from the given matrices.
     */
    public static void computeFrustum(ClippingHelper h, float[] projection, float[] modelview) {
        final float[] p = h.projectionMatrix;
        final float[] m = h.modelviewMatrix;
        final float[] c = h.clippingMatrix;
        System.arraycopy(projection, 0, p, 0, 16);
        System.arraycopy(modelview, 0, m, 0, 16);
        for (int r = 0; r < 4; r++) {
            for (int j = 0; j < 4; j++) {
                c[r * 4 + j] = m[r * 4 + 0] * p[j] + m[r * 4 + 1] * p[4 + j] + m[r * 4 + 2] * p[8 + j] + m[r * 4 + 3] * p[12 + j];
            }
        }
        final float[][] f = h.frustum;
        f[0][0] = c[3] - c[0];
        f[0][1] = c[7] - c[4];
        f[0][2] = c[11] - c[8];
        f[0][3] = c[15] - c[12];
        normalize(f, 0);
        f[1][0] = c[3] + c[0];
        f[1][1] = c[7] + c[4];
        f[1][2] = c[11] + c[8];
        f[1][3] = c[15] + c[12];
        normalize(f, 1);
        f[2][0] = c[3] + c[1];
        f[2][1] = c[7] + c[5];
        f[2][2] = c[11] + c[9];
        f[2][3] = c[15] + c[13];
        normalize(f, 2);
        f[3][0] = c[3] - c[1];
        f[3][1] = c[7] - c[5];
        f[3][2] = c[11] - c[9];
        f[3][3] = c[15] - c[13];
        normalize(f, 3);
        f[4][0] = c[3] - c[2];
        f[4][1] = c[7] - c[6];
        f[4][2] = c[11] - c[10];
        f[4][3] = c[15] - c[14];
        normalize(f, 4);
        f[5][0] = c[3] + c[2];
        f[5][1] = c[7] + c[6];
        f[5][2] = c[11] + c[10];
        f[5][3] = c[15] + c[14];
        normalize(f, 5);
        h.frustum_0_0 = f[0][0];
        h.frustum_0_1 = f[0][1];
        h.frustum_0_2 = f[0][2];
        h.frustum_0_3 = f[0][3];
        h.frustum_1_0 = f[1][0];
        h.frustum_1_1 = f[1][1];
        h.frustum_1_2 = f[1][2];
        h.frustum_1_3 = f[1][3];
        h.frustum_2_0 = f[2][0];
        h.frustum_2_1 = f[2][1];
        h.frustum_2_2 = f[2][2];
        h.frustum_2_3 = f[2][3];
        h.frustum_3_0 = f[3][0];
        h.frustum_3_1 = f[3][1];
        h.frustum_3_2 = f[3][2];
        h.frustum_3_3 = f[3][3];
        h.frustum_4_0 = f[4][0];
        h.frustum_4_1 = f[4][1];
        h.frustum_4_2 = f[4][2];
        h.frustum_4_3 = f[4][3];
        h.frustum_5_0 = f[5][0];
        h.frustum_5_1 = f[5][1];
        h.frustum_5_2 = f[5][2];
        h.frustum_5_3 = f[5][3];
    }

    /** Port of {@code ActiveRenderInfo.updateRenderInfo} using the current {@code Draw.pose()/projection()} tops and {@code Frame} viewport. */
    public static void updateActiveRenderInfo(net.minecraft.EntityPlayer player, boolean thirdPersonFront) {
        Frame.copyViewport(VIEWPORT_BUF);
        final int[] vp = VIEWPORT;
        vp[0] = VIEWPORT_BUF.get(0);
        vp[1] = VIEWPORT_BUF.get(1);
        vp[2] = VIEWPORT_BUF.get(2);
        vp[3] = VIEWPORT_BUF.get(3);
        float var2 = (vp[0] + vp[2]) / 2;
        float var3 = (vp[1] + vp[3]) / 2;
        unProject(var2, var3, 0.0F, Draw.pose().top(), Draw.projection().top(), vp, OBJ);
        ActiveRenderInfo.objectX = OBJ[0];
        ActiveRenderInfo.objectY = OBJ[1];
        ActiveRenderInfo.objectZ = OBJ[2];
        int var4 = thirdPersonFront ? 1 : 0;
        float var5 = player.rotationPitch;
        float var6 = player.rotationYaw;
        ActiveRenderInfo.rotationX = MathHelper.cos(var6 * (float) Math.PI / 180.0F) * (1 - var4 * 2);
        ActiveRenderInfo.rotationZ = MathHelper.sin(var6 * (float) Math.PI / 180.0F) * (1 - var4 * 2);
        ActiveRenderInfo.rotationYZ = -ActiveRenderInfo.rotationZ * MathHelper.sin(var5 * (float) Math.PI / 180.0F) * (1 - var4 * 2);
        ActiveRenderInfo.rotationXY = ActiveRenderInfo.rotationX * MathHelper.sin(var5 * (float) Math.PI / 180.0F) * (1 - var4 * 2);
        ActiveRenderInfo.rotationXZ = MathHelper.cos(var5 * (float) Math.PI / 180.0F);
    }
}
