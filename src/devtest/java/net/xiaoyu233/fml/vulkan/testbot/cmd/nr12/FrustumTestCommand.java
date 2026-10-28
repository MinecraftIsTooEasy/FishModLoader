package net.xiaoyu233.fml.vulkan.testbot.cmd.nr12;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Random;
import java.util.concurrent.Callable;
import net.minecraft.ActiveRenderInfo;
import net.minecraft.ClippingHelper;
import net.minecraft.ClippingHelperImpl;
import net.minecraft.EntityPlayer;
import net.minecraft.MathHelper;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Frame;
import net.xiaoyu233.fml.vulkan.render.world.CameraMatrices;
import net.xiaoyu233.fml.vulkan.testbot.Frozen;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import org.lwjgl.util.glu.GLU;

/**
 * {@code frustumTest [frames=1000]}: for N frozen frames loads a camera (the real player rotation / position of the frame, a perspective with the
 * frame's fov and the MITE view transform) into {@code Draw.pose()/projection()}, then compares bit for bit (raw float bits) the planes of
 * {@link CameraMatrices#computeFrustum} and of {@link ClippingHelperImpl#getInstance()} and the {@link ActiveRenderInfo} values after
 * {@link CameraMatrices#updateActiveRenderInfo} with a reference copied verbatim from {@code ClippingHelperImpl.init} / {@code ActiveRenderInfo}
 * ({@code gluUnProject} is LWJGL's own). Runs right after a frame on the render thread; the Draw matrix stacks are restored.
 */
final class FrustumTestCommand implements DevCommand {
    @Override
    public String name() {
        return "frustumTest";
    }

    @Override
    public String description() {
        return "frustum planes + ActiveRenderInfo of CameraMatrices vs MITE ClippingHelperImpl/ActiveRenderInfo/gluUnProject, bit for bit over N frozen frames";
    }

    @Override
    public void run(Report.Section s, String[] args) throws Exception {
        int frames = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        TestBot bot = TestBot.get();
        if (bot != null && Frozen.active()) {
            bot.client(() -> {
                Frozen.pinClientCounters(bot.minecraft());
                Frozen.beginCapture(Frozen.DEFAULT_PARTIAL_TICKS);
            });
        } else {
            s.warn("frozen", "not in frozen mode (-Pvk.frozen=true): frames are not reproducible, the comparison itself is still exact");
        }
        long[] bad = new long[4]; // computeFrustum planes, ClippingHelperImpl planes, ActiveRenderInfo, singular
        String[] first = new String[4];
        Random rng = new Random(0x5EED12L);
        try {
            for (int i = 0; i < frames; i++) {
                final int frame = i;
                final float yaw = rng.nextFloat() * 360.0F - 180.0F;
                final float pitch = rng.nextFloat() * 180.0F - 90.0F;
                final float fov = 30.0F + rng.nextFloat() * 90.0F;
                final double px = rng.nextDouble() * 64 - 32, py = 64 + rng.nextDouble() * 16, pz = rng.nextDouble() * 64 - 32;
                Callable<Void> c = () -> {
                    checkFrame(frame, yaw, pitch, fov, px, py, pz, bad, first, bot);
                    return null;
                };
                if (bot != null) {
                    bot.afterFrame(c);
                } else {
                    c.call();
                }
                if (bot != null && (i & 7) == 7) {
                    bot.frames(1);
                }
            }
        } finally {
            if (bot != null && Frozen.active()) {
                bot.client(Frozen::endCapture);
            }
        }
        String[] what = {"CameraMatrices.computeFrustum planes", "ClippingHelperImpl.getInstance planes", "ActiveRenderInfo values", "gluUnProject singular result"};
        for (int k = 0; k < 4; k++) {
            if (k == 1 && first[1] != null && first[1].startsWith("skipped")) {
                s.info(what[k], first[1]);
                continue;
            }
            s.check(bad[k] == 0, what[k] + " equal MITE reference bit for bit over " + frames + " frames", bad[k] + " frame(s) differ; first: " + first[k]);
        }
    }

    private static void checkFrame(int frame, float yaw, float pitch, float fov, double px, double py, double pz, long[] bad, String[] first, TestBot bot) {
        var pose = Draw.pose();
        var proj = Draw.projection();
        pose.push();
        proj.push();
        try {
            proj.loadIdentity();
            proj.perspective(fov, 16.0F / 9.0F, 0.05F, 256.0F);
            pose.loadIdentity();
            // MITE orientCamera (first person): rotate(pitch, 1,0,0), rotate(yaw + 180, 0,1,0), translate(-eye)
            pose.rotate(pitch, 1.0F, 0.0F, 0.0F);
            pose.rotate(yaw + 180.0F, 0.0F, 1.0F, 0.0F);
            pose.translate((float) -px, (float) -py, (float) -pz);
            float[] mv = new float[16];
            float[] pr = new float[16];
            System.arraycopy(pose.top(), 0, mv, 0, 16);
            System.arraycopy(proj.top(), 0, pr, 0, 16);
            int[] vp = new int[4];
            IntBuffer vb = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();
            Frame.copyViewport(vb);
            vb.get(0, vp);

            // reference frustum
            ClippingHelper ref = new ClippingHelper();
            referenceInit(ref, pr, mv);

            ClippingHelper mine = new ClippingHelper();
            CameraMatrices.computeFrustum(mine, pr, mv);
            note(bad, first, 0, frame, diff(ref, mine));

            if (bot != null) {
                ClippingHelper impl = ClippingHelperImpl.getInstance();
                note(bad, first, 1, frame, diff(ref, impl));
            } else {
                first[1] = "skipped (bot not running)";
            }

            // reference ActiveRenderInfo
            float[] out = new float[3];
            FloatBuffer fm = direct(mv), fp = direct(pr);
            FloatBuffer oc = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder()).asFloatBuffer();
            float wx = (vp[0] + vp[2]) / 2;
            float wy = (vp[1] + vp[3]) / 2;
            boolean ok = GLU.gluUnProject(wx, wy, 0.0F, fm, fp, vb, oc);
            if (!ok) {
                note(bad, first, 3, frame, "LWJGL gluUnProject singular");
                return;
            }
            out[0] = oc.get(0);
            out[1] = oc.get(1);
            out[2] = oc.get(2);
            EntityPlayer player = bot != null ? bot.minecraft().thePlayer : null;
            if (player == null) {
                first[2] = "skipped (no player)";
                return;
            }
            float oldPitch = player.rotationPitch, oldYaw = player.rotationYaw;
            player.rotationPitch = pitch;
            player.rotationYaw = yaw;
            try {
                boolean third = (frame & 1) == 1;
                CameraMatrices.updateActiveRenderInfo(player, third);
                int var4 = third ? 1 : 0;
                float rx = MathHelper.cos(yaw * (float) Math.PI / 180.0F) * (1 - var4 * 2);
                float rz = MathHelper.sin(yaw * (float) Math.PI / 180.0F) * (1 - var4 * 2);
                float ryz = -rz * MathHelper.sin(pitch * (float) Math.PI / 180.0F) * (1 - var4 * 2);
                float rxy = rx * MathHelper.sin(pitch * (float) Math.PI / 180.0F) * (1 - var4 * 2);
                float rxz = MathHelper.cos(pitch * (float) Math.PI / 180.0F);
                StringBuilder sb = new StringBuilder();
                cmp(sb, "objectX", out[0], ActiveRenderInfo.objectX);
                cmp(sb, "objectY", out[1], ActiveRenderInfo.objectY);
                cmp(sb, "objectZ", out[2], ActiveRenderInfo.objectZ);
                cmp(sb, "rotationX", rx, ActiveRenderInfo.rotationX);
                cmp(sb, "rotationZ", rz, ActiveRenderInfo.rotationZ);
                cmp(sb, "rotationYZ", ryz, ActiveRenderInfo.rotationYZ);
                cmp(sb, "rotationXY", rxy, ActiveRenderInfo.rotationXY);
                cmp(sb, "rotationXZ", rxz, ActiveRenderInfo.rotationXZ);
                note(bad, first, 2, frame, sb.length() == 0 ? null : sb.toString());
            } finally {
                player.rotationPitch = oldPitch;
                player.rotationYaw = oldYaw;
            }
        } finally {
            proj.pop();
            pose.pop();
        }
    }

    private static FloatBuffer direct(float[] a) {
        FloatBuffer b = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(a).flip();
        return b;
    }

    private static void note(long[] bad, String[] first, int k, int frame, String diff) {
        if (diff != null) {
            bad[k]++;
            if (first[k] == null) {
                first[k] = "frame " + frame + ": " + diff;
            }
        }
    }

    private static void cmp(StringBuilder sb, String n, float ref, float got) {
        if (Float.floatToRawIntBits(ref) != Float.floatToRawIntBits(got)) {
            sb.append(n).append(" ref=").append(ref).append(" got=").append(got).append("; ");
        }
    }

    private static String diff(ClippingHelper a, ClippingHelper b) {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < 6; p++) {
            for (int j = 0; j < 4; j++) {
                cmp(sb, "frustum[" + p + "][" + j + "]", a.frustum[p][j], b.frustum[p][j]);
            }
        }
        for (int j = 0; j < 16; j++) {
            cmp(sb, "clippingMatrix[" + j + "]", a.clippingMatrix[j], b.clippingMatrix[j]);
            cmp(sb, "projectionMatrix[" + j + "]", a.projectionMatrix[j], b.projectionMatrix[j]);
            cmp(sb, "modelviewMatrix[" + j + "]", a.modelviewMatrix[j], b.modelviewMatrix[j]);
        }
        float[] fa = flat(a), fb = flat(b);
        for (int j = 0; j < 24; j++) {
            cmp(sb, "frustum_" + (j / 4) + "_" + (j % 4), fa[j], fb[j]);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static float[] flat(ClippingHelper h) {
        return new float[] {h.frustum_0_0, h.frustum_0_1, h.frustum_0_2, h.frustum_0_3, h.frustum_1_0, h.frustum_1_1, h.frustum_1_2, h.frustum_1_3, h.frustum_2_0, h.frustum_2_1,
            h.frustum_2_2, h.frustum_2_3, h.frustum_3_0, h.frustum_3_1, h.frustum_3_2, h.frustum_3_3, h.frustum_4_0, h.frustum_4_1, h.frustum_4_2, h.frustum_4_3, h.frustum_5_0,
            h.frustum_5_1, h.frustum_5_2, h.frustum_5_3};
    }

    // ================================================================================================ reference (ClippingHelperImpl.init, verbatim arithmetic)

    private static void normalize(float[][] f, int p) {
        float var3 = MathHelper.sqrt_float(f[p][0] * f[p][0] + f[p][1] * f[p][1] + f[p][2] * f[p][2]);
        f[p][0] = f[p][0] / var3;
        f[p][1] = f[p][1] / var3;
        f[p][2] = f[p][2] / var3;
        f[p][3] = f[p][3] / var3;
    }

    private static void referenceInit(ClippingHelper h, float[] projection, float[] modelview) {
        System.arraycopy(projection, 0, h.projectionMatrix, 0, 16);
        System.arraycopy(modelview, 0, h.modelviewMatrix, 0, 16);
        float[] m = h.modelviewMatrix;
        float[] p = h.projectionMatrix;
        float[] c = h.clippingMatrix;
        for (int r = 0; r < 4; r++) {
            for (int col = 0; col < 4; col++) {
                // MITE writes the sums out in this exact left to right order: ((m0*p0 + m1*p4) + m2*p8) + m3*p12
                c[r * 4 + col] = m[r * 4] * p[col] + m[r * 4 + 1] * p[4 + col] + m[r * 4 + 2] * p[8 + col] + m[r * 4 + 3] * p[12 + col];
            }
        }
        float[][] f = h.frustum;
        int[][] sign = {{-1, 0}, {1, 0}, {1, 1}, {-1, 1}, {-1, 2}, {1, 2}};
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 4; j++) {
                float a = c[3 + 4 * j];
                float b = c[sign[i][1] + 4 * j];
                f[i][j] = sign[i][0] < 0 ? a - b : a + b;
            }
            normalize(f, i);
        }
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
}
