package net.xiaoyu233.fml.vulkan.testbot.cmd.nr06;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import net.xiaoyu233.fml.vulkan.render.state.AttribMask;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.ColorMaterial;
import net.xiaoyu233.fml.vulkan.render.state.DepthFunc;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Face;
import net.xiaoyu233.fml.vulkan.render.state.FogMode;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import net.xiaoyu233.fml.vulkan.render.state.Lighting;
import net.xiaoyu233.fml.vulkan.render.state.MaterialKey;
import net.xiaoyu233.fml.vulkan.render.state.PoseStack;
import net.xiaoyu233.fml.vulkan.render.state.RenderType;
import net.xiaoyu233.fml.vulkan.render.state.VertexFormat;
import net.xiaoyu233.fml.vulkan.render.tess.MeshWorkerThread;
import net.xiaoyu233.fml.vulkan.render.tess.NativeTess;
import net.xiaoyu233.fml.vulkan.render.tess.TessBridge;
import net.xiaoyu233.fml.vulkan.render.testing.DrawCapture;
import net.xiaoyu233.fml.vulkan.render.testing.RecordedDraw;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import org.lwjgl.opengl.GL11;

/**
 * {@code mathTest} of NR-06: (1) {@link PoseStack} equals the phase-1 {@code gl.state.MatrixStack} bit for bit for 10k random operation
 * sequences and {@code perspective} equals lwjgl_util's {@code gluPerspective}; (2) {@link Lighting} / {@link NativeTess} finalisation
 * checked with {@link DrawCapture} against a double precision reference of GL 1.5 per-vertex lighting / colour / uv rules for 10k random
 * (state, vertex) cases within 1/255 per channel; (3) primitive conversion; (4) {@link GlShim} maps every Appendix C constant; (5) material
 * key, attribute stack; (6) {@code TessBridge.current()} at most 2 ns per call. The state parts run on the render thread right after a
 * frame (the render-thread rule of {@code Draw}) and restore the {@code Draw} state they touched.
 */
final class MathTestCommand implements DevCommand {
    @Override
    public String name() {
        return "mathTest";
    }

    @Override
    public String description() {
        return "PoseStack vs MatrixStack, Lighting/NativeTess vs a GL 1.5 reference, GlShim constants, TessBridge speed";
    }

    @Override
    public void run(Report.Section s, String[] args) throws Exception {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 10_000;
        poseStackVsMatrixStack(s, iterations);
        onRenderThread(() -> {
            perspectiveVsGlu(s);
            lightingReference(s, iterations);
            primitiveConversion(s);
            glShimConstants(s);
            materialKeyAndAttribs(s);
            return null;
        });
        tessBridge(s);
    }

    // ================================================================================================ plumbing

    /** Runs {@code c} on the render thread right after a frame when the bot is up, directly otherwise (offline run of the command). */
    private static <T> T onRenderThread(Callable<T> c) throws Exception {
        try {
            Field f = TestBot.class.getDeclaredField("instance");
            f.setAccessible(true);
            Object bot = f.get(null);
            if (bot != null) {
                return ((TestBot) bot).afterFrame(c);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // fall through: run directly
        }
        return c.call();
    }

    private static boolean sameBits(float a, float b) {
        return Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b);
    }

    // ================================================================================================ 1. PoseStack

    private static void poseStackVsMatrixStack(Report.Section s, int iterations) throws Exception {
        Class<?> msClass = Class.forName("net.xiaoyu233.fml.vulkan.gl.state.MatrixStack");
        Constructor<?> ctor = msClass.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        Method push = m(msClass, "push"), pop = m(msClass, "pop"), identity = m(msClass, "loadIdentity"), top = m(msClass, "top"),
                load = m(msClass, "load", float[].class), mult = m(msClass, "mult", float[].class),
                translate = m(msClass, "translate", float.class, float.class, float.class),
                scale = m(msClass, "scale", float.class, float.class, float.class),
                rotate = m(msClass, "rotate", float.class, float.class, float.class, float.class),
                ortho = m(msClass, "ortho", double.class, double.class, double.class, double.class, double.class, double.class),
                frustum = m(msClass, "frustum", double.class, double.class, double.class, double.class, double.class, double.class);
        Random rnd = new Random(0x5EED06L);
        int mismatches = 0;
        int checked = 0;
        String first = null;
        for (int it = 0; it < iterations; it++) {
            Object ref = ctor.newInstance(64);
            PoseStack pose = new PoseStack(64);
            int depth = 0;
            int ops = 8 + rnd.nextInt(40);
            for (int k = 0; k < ops; k++) {
                int op = rnd.nextInt(12);
                float a = rnd.nextFloat() * 20f - 10f, b = rnd.nextFloat() * 20f - 10f, c = rnd.nextFloat() * 20f - 10f, d = rnd.nextFloat() * 720f - 360f;
                switch (op) {
                    case 0, 1 -> {
                        ref_call(translate, ref, a, b, c);
                        pose.translate(a, b, c);
                    }
                    case 2 -> {
                        double x = a * 0.37, y = b * 0.37, z = c * 0.37;
                        ref_call(translate, ref, (float) x, (float) y, (float) z);
                        pose.translate(x, y, z);
                    }
                    case 3 -> {
                        ref_call(scale, ref, a * 0.2f, b * 0.2f, c * 0.2f);
                        pose.scale(a * 0.2f, b * 0.2f, c * 0.2f);
                    }
                    case 4, 5 -> {
                        ref_call(rotate, ref, d, a, b, c);
                        pose.rotate(d, a, b, c);
                    }
                    case 6 -> {
                        if (depth < 50) {
                            ref_call(push, ref);
                            pose.push();
                            depth++;
                        }
                    }
                    case 7 -> {
                        if (depth > 0) {
                            ref_call(pop, ref);
                            pose.pop();
                            depth--;
                        }
                    }
                    case 8 -> {
                        ref_call(identity, ref);
                        pose.loadIdentity();
                    }
                    case 9 -> {
                        double l = a, r = a + 1 + Math.abs(b), bt = c, t = c + 1 + Math.abs(a);
                        ref_call(ortho, ref, l, r, bt, t, -1.0, 1000.0 + d);
                        pose.ortho(l, r, bt, t, -1.0, 1000.0 + d);
                    }
                    case 10 -> {
                        double l = -1 - Math.abs(a), r = 1 + Math.abs(b), bt = -1 - Math.abs(c), t = 1 + Math.abs(d) * 0.01;
                        ref_call(frustum, ref, l, r, bt, t, 0.05, 256.0);
                        pose.frustum(l, r, bt, t, 0.05, 256.0);
                    }
                    default -> {
                        float[] mm = new float[16];
                        for (int i = 0; i < 16; i++) {
                            mm[i] = rnd.nextFloat() * 4f - 2f;
                        }
                        if (rnd.nextBoolean()) {
                            ref_call(mult, ref, (Object) mm);
                            pose.mul(mm);
                        } else {
                            ref_call(load, ref, (Object) mm);
                            pose.load(mm);
                        }
                    }
                }
                float[] expected = (float[]) top.invoke(ref);
                float[] actual = pose.top();
                checked++;
                for (int i = 0; i < 16; i++) {
                    if (!sameBits(expected[i], actual[i])) {
                        mismatches++;
                        if (first == null) {
                            first = "iteration " + it + " op " + op + " element " + i + ": MatrixStack " + expected[i] + " PoseStack " + actual[i];
                        }
                        break;
                    }
                }
            }
        }
        s.check(mismatches == 0, "PoseStack == MatrixStack bit for bit (" + iterations + " random sequences, " + checked + " compared matrices)",
                mismatches + " mismatching matrices, first: " + first);
        // version counter and cached derived matrices
        PoseStack p = new PoseStack(4);
        int v0 = p.version();
        p.translate(1f, 2f, 3f);
        s.check(p.version() != v0, "PoseStack.version changes with the top matrix", "version unchanged after translate");
        p.scale(2f, 2f, 2f);
        s.check(Math.abs(p.rescaleFactor() - 2f) < 1e-6f, "rescaleFactor of a uniform scale 2 is 2", "got " + p.rescaleFactor());
        float[] nmx = p.normalMatrix();
        s.check(Math.abs(nmx[0] - 0.5f) < 1e-6f && Math.abs(nmx[4] - 0.5f) < 1e-6f && Math.abs(nmx[8] - 0.5f) < 1e-6f,
                "normalMatrix of a uniform scale 2 is 0.5 * identity", "got " + java.util.Arrays.toString(nmx));
    }

    private static Method m(Class<?> c, String name, Class<?>... types) throws Exception {
        Method m = c.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m;
    }

    private static void ref_call(Method m, Object target, Object... args) throws Exception {
        m.invoke(target, args);
    }

    // ================================================================================================ 1b. perspective

    private static void perspectiveVsGlu(Report.Section s) {
        Random rnd = new Random(77);
        int bad = 0;
        FloatBuffer buf = java.nio.ByteBuffer.allocateDirect(64).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        GL11.glMatrixMode(5889);
        GL11.glPushMatrix();
        try {
            for (int i = 0; i < 200; i++) {
                float fov = 10f + rnd.nextFloat() * 110f, aspect = 0.3f + rnd.nextFloat() * 3f, near = 0.01f + rnd.nextFloat(), far = 20f + rnd.nextFloat() * 500f;
                GL11.glLoadIdentity();
                org.lwjgl.util.glu.Project.gluPerspective(fov, aspect, near, far);
                buf.clear();
                GL11.glGetFloat(2983, buf);
                PoseStack p = new PoseStack(2);
                p.perspective(fov, aspect, near, far);
                for (int k = 0; k < 16; k++) {
                    if (!sameBits(buf.get(k), p.top()[k])) {
                        bad++;
                        break;
                    }
                }
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glMatrixMode(5888);
        }
        s.check(bad == 0, "PoseStack.perspective == lwjgl_util gluPerspective bit for bit (200 cases)", bad + " mismatching cases");
    }

    // ================================================================================================ 2. lighting / finalisation

    private static final double[] WORLD0 = norm(0.2f, 1.0, -0.7f), WORLD1 = norm(-0.2f, 1.0, 0.7f);

    private static double[] norm(double x, double y, double z) {
        double len = (float) Math.sqrt(x * x + y * y + z * z);
        return new double[] {(float) (x / len), (float) (y / len), (float) (z / len)};
    }

    private static void lightingReference(Report.Section s, int cases) {
        Random rnd = new Random(0xC0FFEE);
        NativeTess tess = TessBridge.renderThread();
        PoseStack pose = Draw.pose();
        PoseStack uv = Draw.uvMatrix();
        Draw.pushAttrib(1023);
        pose.push();
        uv.push();
        int bad = 0, badPos = 0, badUv = 0, badLight = 0;
        int worst = 0;
        String first = null;
        try (DrawCapture capture = DrawCapture.open()) {
            for (int c = 0; c < cases; c++) {
                capture.clear();
                pose.loadIdentity();
                int ops = 1 + rnd.nextInt(5);
                for (int k = 0; k < ops; k++) {
                    switch (rnd.nextInt(3)) {
                        case 0 -> pose.translate(rnd.nextFloat() * 8 - 4, rnd.nextFloat() * 8 - 4, rnd.nextFloat() * 8 - 4);
                        case 1 -> pose.rotate(rnd.nextFloat() * 360f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f + 0.1f);
                        default -> {
                            if (rnd.nextInt(3) == 0) {
                                float u = 0.3f + rnd.nextFloat() * 2f;
                                pose.scale(u, u, u);
                            } else {
                                pose.scale(0.3f + rnd.nextFloat() * 2f, 0.3f + rnd.nextFloat() * 2f, 0.3f + rnd.nextFloat() * 2f);
                            }
                        }
                    }
                }
                uv.loadIdentity();
                boolean uvMat = rnd.nextInt(4) == 0;
                if (uvMat) {
                    uv.translate(rnd.nextFloat(), rnd.nextFloat(), 0f);
                }
                boolean lit = rnd.nextBoolean();
                int cmMode = rnd.nextInt(4); // 0 off, 1 amb+diff, 2 ambient, 3 diffuse
                boolean normalize = rnd.nextBoolean();
                boolean rescale = rnd.nextBoolean();
                Draw.lighting(false);
                if (lit) {
                    Lighting.enableStandard();
                    Draw.lighting(true);
                    Draw.colorMaterialEnabled(cmMode != 0);
                    if (cmMode != 0) {
                        Draw.colorMaterial(cmMode == 1 ? ColorMaterial.AMBIENT_AND_DIFFUSE : cmMode == 2 ? ColorMaterial.AMBIENT : ColorMaterial.DIFFUSE);
                    }
                } else {
                    Lighting.disable();
                }
                Draw.normalize(normalize);
                Draw.rescaleNormal(rescale);
                float cr = rnd.nextFloat(), cg = rnd.nextFloat(), cb = rnd.nextFloat(), ca = rnd.nextFloat();
                Draw.color(cr, cg, cb, ca);
                float[] cn = randomUnit(rnd);
                Draw.normal(cn[0], cn[1], cn[2]);
                Draw.light(rnd.nextInt(241), rnd.nextInt(241));
                Draw.overlayNone();

                boolean vColor = rnd.nextBoolean(), vNormal = rnd.nextBoolean(), vTex = rnd.nextBoolean(), vLight = rnd.nextBoolean();
                int n = 4 * (1 + rnd.nextInt(3));
                double[][] pos = new double[n][3];
                double[] vu = new double[n], vv = new double[n];
                int[] vcol = new int[n];
                float[][] vnrm = new float[n][];
                int[] vlight = new int[n];
                tess.startDrawingQuads();
                for (int i = 0; i < n; i++) {
                    if (vColor) {
                        int cr8 = rnd.nextInt(256), cg8 = rnd.nextInt(256), cb8 = rnd.nextInt(256), ca8 = rnd.nextInt(256);
                        tess.setColorRGBA(cr8, cg8, cb8, ca8);
                        vcol[i] = ca8 << 24 | cb8 << 16 | cg8 << 8 | cr8;
                    }
                    if (vNormal) {
                        float[] vn = randomUnit(rnd);
                        tess.setNormal(vn[0], vn[1], vn[2]);
                        vnrm[i] = vn;
                    }
                    if (vTex) {
                        vu[i] = rnd.nextDouble();
                        vv[i] = rnd.nextDouble();
                        tess.setTextureUV(vu[i], vv[i]);
                    }
                    if (vLight) {
                        vlight[i] = (rnd.nextInt(16) * 16) << 16 | rnd.nextInt(16) * 16;
                        tess.setBrightness(vlight[i]);
                    }
                    pos[i][0] = rnd.nextDouble() * 6 - 3;
                    pos[i][1] = rnd.nextDouble() * 6 - 3;
                    pos[i][2] = rnd.nextDouble() * 6 - 3;
                    tess.addVertex(pos[i][0], pos[i][1], pos[i][2]);
                    if (!vNormal) {
                        vnrm[i] = null;
                    }
                }
                tess.draw();
                if (capture.draws().size() != 1 || capture.draws().get(0).vertexCount() != n) {
                    bad++;
                    if (first == null) {
                        first = "case " + c + ": " + capture.draws().size() + " draws, vertices " + (capture.draws().isEmpty() ? 0 : capture.draws().get(0).vertexCount()) + " expected " + n;
                    }
                    continue;
                }
                RecordedDraw rd = capture.draws().get(0);
                float[] mv = pose.top();
                double[] inv = inverse3(mv);
                double rescaleF = 1.0 / Math.sqrt(inv[2] * inv[2] + inv[5] * inv[5] + inv[8] * inv[8]);
                double[] l0 = eyeDir(mv, WORLD0), l1 = eyeDir(mv, WORLD1);
                for (int i = 0; i < n; i++) {
                    double[] p = pos[i];
                    double ex = mv[0] * (float) p[0] + mv[4] * (float) p[1] + mv[8] * (float) p[2] + mv[12];
                    double ey = mv[1] * (float) p[0] + mv[5] * (float) p[1] + mv[9] * (float) p[2] + mv[13];
                    double ez = mv[2] * (float) p[0] + mv[6] * (float) p[1] + mv[10] * (float) p[2] + mv[14];
                    if (Math.abs(ex - rd.x(i)) > 1e-3 + Math.abs(ex) * 1e-5 || Math.abs(ey - rd.y(i)) > 1e-3 + Math.abs(ey) * 1e-5
                            || Math.abs(ez - rd.z(i)) > 1e-3 + Math.abs(ez) * 1e-5) {
                        badPos++;
                    }
                    double eu = vTex ? vu[i] : 0.0, ev = vTex ? vv[i] : 0.0;
                    float[] um = uv.top();
                    double exu = um[0] * (float) eu + um[4] * (float) ev + um[12];
                    double exv = um[1] * (float) eu + um[5] * (float) ev + um[13];
                    if (Math.abs(exu - rd.u(i)) > 1e-5 || Math.abs(exv - rd.v(i)) > 1e-5) {
                        badUv++;
                    }
                    int expectLight = vLight ? vlight[i] : (Math.round((float) Draw.lightV()) << 16 | Math.round((float) Draw.lightU()));
                    if (rd.packedLight(i) != expectLight) {
                        badLight++;
                    }
                    int baseAbgr = vColor ? vcol[i] : Draw.colorAbgr();
                    int expected = baseAbgr;
                    if (lit) {
                        float[] on = vNormal ? decodeNormal(vnrm[i]) : new float[] {cn[0], cn[1], cn[2]};
                        double nx = inv[0] * on[0] + inv[1] * on[1] + inv[2] * on[2];
                        double ny = inv[3] * on[0] + inv[4] * on[1] + inv[5] * on[2];
                        double nz = inv[6] * on[0] + inv[7] * on[1] + inv[8] * on[2];
                        // inverse transpose: n' = (M^-1)^T n, with inv stored column-major as inv[c*3 + r]; (M^-1)^T n has components sum_r inv[c*3+r]*n_r
                        nx = inv[0] * on[0] + inv[1] * on[1] + inv[2] * on[2];
                        ny = inv[3] * on[0] + inv[4] * on[1] + inv[5] * on[2];
                        nz = inv[6] * on[0] + inv[7] * on[1] + inv[8] * on[2];
                        if (normalize) {
                            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                            nx /= len;
                            ny /= len;
                            nz /= len;
                        } else if (rescale) {
                            nx *= rescaleF;
                            ny *= rescaleF;
                            nz *= rescaleF;
                        }
                        double d = Math.max(nx * l0[0] + ny * l0[1] + nz * l0[2], 0) + Math.max(nx * l1[0] + ny * l1[1] + nz * l1[2], 0);
                        boolean ambFromColor = cmMode == 1 || cmMode == 2, diffFromColor = cmMode == 1 || cmMode == 3;
                        int er = lightChannel(baseAbgr & 0xFF, ambFromColor, diffFromColor, d);
                        int eg = lightChannel(baseAbgr >>> 8 & 0xFF, ambFromColor, diffFromColor, d);
                        int eb = lightChannel(baseAbgr >>> 16 & 0xFF, ambFromColor, diffFromColor, d);
                        int ea = diffFromColor ? baseAbgr >>> 24 : 255;
                        expected = ea << 24 | eb << 16 | eg << 8 | er;
                    }
                    int got = rd.abgr(i);
                    int diff = 0;
                    for (int sh = 0; sh < 32; sh += 8) {
                        diff = Math.max(diff, Math.abs((got >>> sh & 0xFF) - (expected >>> sh & 0xFF)));
                    }
                    worst = Math.max(worst, diff);
                    if (diff > 1) {
                        bad++;
                        if (first == null) {
                            first = String.format("case %d vertex %d: lit=%b cm=%d norm=%b resc=%b got %08x expected %08x", c, i, lit, cmMode, normalize, rescale, got, expected);
                        }
                    }
                }
            }
        } finally {
            uv.pop();
            pose.pop();
            Draw.popAttrib();
            Draw.overlayNone();
        }
        s.check(bad == 0 && badPos == 0 && badUv == 0 && badLight == 0,
                "NativeTess finalisation == GL 1.5 double reference (" + cases + " random cases, colour within 1/255, worst channel diff " + worst + ")",
                "colour " + bad + ", position " + badPos + ", uv " + badUv + ", light " + badLight + "; first: " + first);
    }

    private static float[] decodeNormal(float[] unit) {
        // the Tessellator quantises normals to bytes ((byte)(n * 127)) and GL decodes a BYTE normal as (2c + 1) / 255
        float[] r = new float[3];
        for (int i = 0; i < 3; i++) {
            byte b = (byte) (unit[i] * 127.0F);
            r[i] = (2f * b + 1f) / 255f;
        }
        return r;
    }

    private static int lightChannel(int c, boolean ambFromColor, boolean diffFromColor, double d) {
        double cf = c / 255.0;
        double amb = (ambFromColor ? cf : 0.2) * (Draw.lightingEnabled() ? 0.4 : 0.2);
        double dif = (diffFromColor ? cf : 0.8) * 0.6 * d;
        int v = (int) Math.round((amb + dif) * 255.0);
        return v < 0 ? 0 : Math.min(v, 255);
    }

    private static float[] randomUnit(Random rnd) {
        double x, y, z, len;
        do {
            x = rnd.nextGaussian();
            y = rnd.nextGaussian();
            z = rnd.nextGaussian();
            len = Math.sqrt(x * x + y * y + z * z);
        } while (len < 0.1);
        return new float[] {(float) (x / len), (float) (y / len), (float) (z / len)};
    }

    /** Inverse of the upper 3x3 in double, column-major (inv[c*3 + r] = row r column c of the inverse), then used as the transpose. */
    private static double[] inverse3(float[] m) {
        double a = m[0], b = m[4], c = m[8], d = m[1], e = m[5], f = m[9], g = m[2], h = m[6], i = m[10];
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        double[] r = new double[9];
        // row-major inverse entries inv[row][col]
        double i00 = (e * i - f * h) / det, i01 = (c * h - b * i) / det, i02 = (b * f - c * e) / det;
        double i10 = (f * g - d * i) / det, i11 = (a * i - c * g) / det, i12 = (c * d - a * f) / det;
        double i20 = (d * h - e * g) / det, i21 = (b * g - a * h) / det, i22 = (a * e - b * d) / det;
        // store so that out[c*3 + r] = inv[c][r] (inverse transpose applied as sum_r out[c*3+r] * n_r gives (inv^T n)_c ... see use)
        r[0] = i00;
        r[1] = i10;
        r[2] = i20;
        r[3] = i01;
        r[4] = i11;
        r[5] = i21;
        r[6] = i02;
        r[7] = i12;
        r[8] = i22;
        // r[c*3 + r] = inv[r][c]: the third ROW of the inverse is r[2], r[5], r[8] (as rescaleFactor needs);
        // (inv^T n)_k = sum_j inv[j][k] n_j = sum_j r[k*3 + j] n_j  -> matches the loops above
        return r;
    }

    private static double[] eyeDir(float[] m, double[] w) {
        double x = m[0] * (float) w[0] + m[4] * (float) w[1] + m[8] * (float) w[2];
        double y = m[1] * (float) w[0] + m[5] * (float) w[1] + m[9] * (float) w[2];
        double z = m[2] * (float) w[0] + m[6] * (float) w[1] + m[10] * (float) w[2];
        double len = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / len, y / len, z / len};
    }

    // ================================================================================================ 3. primitives

    private static void primitiveConversion(Report.Section s) {
        NativeTess tess = TessBridge.renderThread();
        PoseStack pose = Draw.pose();
        Draw.pushAttrib(1023);
        pose.push();
        pose.loadIdentity();
        Draw.lighting(false);
        boolean ok = true;
        String why = "";
        try (DrawCapture capture = DrawCapture.open()) {
            // triangle strip of 6 vertices -> 4 triangles with GL winding
            tess.startDrawing(5);
            for (int i = 0; i < 6; i++) {
                tess.addVertex(i, i & 1, 0);
            }
            tess.draw();
            RecordedDraw rd = capture.draws().isEmpty() ? null : capture.draws().get(0);
            // expected corner x per quad: tri0 (0,1,2), tri1 (2,1,3) i.e. (i+1, i, i+2), tri2 (2,3,4), tri3 (4,3,5)
            int[][] expect = {{0, 1, 2, 2}, {2, 1, 3, 3}, {2, 3, 4, 4}, {4, 3, 5, 5}};
            if (rd == null || rd.vertexCount() != 16) {
                ok = false;
                why = "strip produced " + (rd == null ? 0 : rd.vertexCount()) + " vertices, expected 16";
            } else {
                for (int q = 0; q < 4 && ok; q++) {
                    for (int c = 0; c < 4; c++) {
                        if (Math.round(rd.x(q * 4 + c)) != expect[q][c]) {
                            ok = false;
                            why = "strip triangle " + q + " corner " + c + " is vertex " + rd.x(q * 4 + c) + ", expected " + expect[q][c];
                            break;
                        }
                    }
                }
            }
            capture.clear();
            // fan of 5 vertices -> 3 triangles (0,k+1,k+2)
            tess.startDrawing(6);
            for (int i = 0; i < 5; i++) {
                tess.addVertex(i, 0, 0);
            }
            tess.draw();
            rd = capture.draws().isEmpty() ? null : capture.draws().get(0);
            if (ok && (rd == null || rd.vertexCount() != 12 || Math.round(rd.x(4)) != 0 || Math.round(rd.x(5)) != 2 || Math.round(rd.x(6)) != 3)) {
                ok = false;
                why = "fan conversion wrong: " + rd;
            }
            capture.clear();
            // quad strip of 6 vertices -> 2 quads (0,1,3,2), (2,3,5,4)
            tess.startDrawing(8);
            for (int i = 0; i < 6; i++) {
                tess.addVertex(i, 0, 0);
            }
            tess.draw();
            rd = capture.draws().isEmpty() ? null : capture.draws().get(0);
            if (ok && (rd == null || rd.vertexCount() != 8 || Math.round(rd.x(2)) != 3 || Math.round(rd.x(3)) != 2 || Math.round(rd.x(6)) != 5)) {
                ok = false;
                why = "quad strip conversion wrong: " + rd;
            }
            capture.clear();
            // lines: strip of 4 vertices -> 3 segments, loop of 3 -> 3 segments
            tess.startDrawing(3);
            for (int i = 0; i < 4; i++) {
                tess.addVertex(i, 0, 0);
            }
            tess.draw();
            rd = capture.draws().isEmpty() ? null : capture.draws().get(0);
            if (ok && (rd == null || rd.lineCount() != 3)) {
                ok = false;
                why = "line strip produced " + (rd == null ? 0 : rd.lineCount()) + " segments";
            }
            capture.clear();
            tess.startDrawing(2);
            for (int i = 0; i < 3; i++) {
                tess.addVertex(i, i, 0);
            }
            tess.draw();
            rd = capture.draws().isEmpty() ? null : capture.draws().get(0);
            if (ok && (rd == null || rd.lineCount() != 3 || rd.type().format() != VertexFormat.LINE)) {
                ok = false;
                why = "line loop wrong: " + rd;
            }
            // MESH target: raw quads reach the sink
            int[] count = new int[1];
            NativeTess worker = TessBridge.createWorkerTess((raw, ints, vertices) -> count[0] += vertices);
            worker.startDrawingQuads();
            for (int i = 0; i < 8; i++) {
                worker.addVertexWithUV(i, 0, 0, 0, 0);
            }
            worker.draw();
            if (ok && count[0] != 8) {
                ok = false;
                why = "MESH target delivered " + count[0] + " vertices, expected 8";
            }
        } finally {
            pose.pop();
            Draw.popAttrib();
        }
        s.check(ok, "NativeTess primitive conversion (strip winding, fan, quad strip, lines, MESH sink)", why);

        // colour packing semantics: MITE unclamped vs vanilla clamped
        NativeTess t = new NativeTess();
        t.startDrawingQuads();
        t.setColorRGBA_MITE(300, 20, 10, 255);
        t.addVertex(0, 0, 0);
        int mite = t.rawBuffer()[5];
        t.setColorRGBA(300, 20, 10, 255);
        t.addVertex(0, 0, 0);
        int vanilla = t.rawBuffer()[13];
        t.reset();
        s.check(vanilla == (255 << 24 | 10 << 16 | 20 << 8 | 255) && mite == (255 << 24 | 10 << 16 | 20 << 8 | 300),
                "setColorRGBA clamps, setColorRGBA_MITE does not", String.format("vanilla %08x mite %08x", vanilla, mite));
    }

    // ================================================================================================ 4. GlShim constants

    private static void glShimConstants(Report.Section s) {
        StringBuilder bad = new StringBuilder();
        Draw.pushAttrib(1023);
        try {
            int[] caps = {3042, 3553, 2896, 3008, 32826, 2977, 2929, 2884, 2912, 2903, 32823, 3058};
            for (int cap : caps) {
                GlShim.enable(cap);
                boolean on = readCap(cap);
                GlShim.disable(cap);
                boolean off = readCap(cap);
                if (!on || off) {
                    bad.append("cap ").append(cap).append(' ');
                }
            }
            int[] blend = {0, 1, 768, 769, 770, 771, 772, 773, 774, 775};
            for (int src : blend) {
                for (int dst : blend) {
                    GlShim.blendFunc(src, dst);
                    if (Draw.blendSrc() != BlendFactor.of(src) || Draw.blendDst() != BlendFactor.of(dst)) {
                        bad.append("blend ").append(src).append('/').append(dst).append(' ');
                    }
                }
            }
            int[] depth = {512, 513, 514, 515, 516, 517, 518, 519};
            for (int f : depth) {
                GlShim.depthFunc(f);
                if (Draw.depthFuncState() != DepthFunc.of(f) || Draw.depthFuncState().ordinal() != f - 512) {
                    bad.append("depth ").append(f).append(' ');
                }
            }
            GlShim.cullFace(1028);
            boolean front = Draw.cullFaceState() == Face.FRONT;
            GlShim.cullFace(1029);
            if (!front || Draw.cullFaceState() != Face.BACK) {
                bad.append("cullFace ");
            }
            GlShim.alphaFunc(516, 0.37f);
            if (Draw.alphaRef() != 0.37f) {
                bad.append("alphaFunc ");
            }
            GlShim.shadeModel(7424);
            boolean flat = !Draw.smoothShadingEnabled();
            GlShim.shadeModel(7425);
            if (!flat || !Draw.smoothShadingEnabled()) {
                bad.append("shadeModel ");
            }
            GlShim.colorMask(true, false, true, false);
            if (Draw.colorMaskBits() != 5) {
                bad.append("colorMask ");
            }
            GlShim.polygonOffset(1.5f, 2.5f);
            GlShim.lineWidth(3f);
            if (Draw.polygonOffsetFactor() != 1.5f || Draw.polygonOffsetUnits() != 2.5f || Draw.lineWidthPx() != 3f) {
                bad.append("polygonOffset/lineWidth ");
            }
            GlShim.color4f(0.25f, 0.5f, 0.75f, 1f);
            GlShim.normal3f(0f, 1f, 0f);
            if (Draw.colorR() != 0.25f || Draw.colorB() != 0.75f || Draw.normalY() != 1f) {
                bad.append("color/normal ");
            }
            // matrices: matrixMode 5888 / 5889 / 5890 select the stacks
            PoseStack[] stacks = {Draw.pose(), Draw.projection(), Draw.uvMatrix()};
            int[] modes = {5888, 5889, 5890};
            for (int i = 0; i < 3; i++) {
                stacks[i].push();
                GlShim.matrixMode(modes[i]);
                float v0 = stacks[i].top()[12];
                GlShim.translatef(5f, 0f, 0f);
                if (stacks[i].top()[12] != v0 + 5f) {
                    bad.append("matrixMode ").append(modes[i]).append(' ');
                }
                GlShim.loadIdentity();
                GlShim.ortho(0, 10, 0, 10, -1, 1);
                GlShim.pushMatrix();
                GlShim.popMatrix();
                GlShim.matrixMode(5888);
                stacks[i].pop();
            }
            // texture parameters collapse to one Textures call; a wrong target is forwarded (fallback counter)
            long before = GlShim.fallbackCount();
            GlShim.enable(16384);
            GlShim.disable(16384);
            if (GlShim.fallbackCount() != before + 2) {
                bad.append("unhandled counter ");
            }
        } finally {
            Draw.popAttrib();
        }
        s.check(bad.length() == 0, "GlShim maps every Appendix C constant (12 caps, 100 blend pairs, 8 depth funcs, cull, alpha, shade, mask, matrices)", "mismatches: " + bad);

        // attribute decode of the two MITE masks
        int a = AttribMask.decode(2929), b = AttribMask.decode(2932);
        boolean aOk = a == (AttribMask.CURRENT | AttribMask.LIGHTING | AttribMask.DEPTH | AttribMask.VIEWPORT);
        boolean bOk = b == (AttribMask.LINE | AttribMask.LIGHTING | AttribMask.DEPTH | AttribMask.VIEWPORT);
        s.check(aOk && bOk, "AttribMask.decode(2929/2932) = CURRENT|LIGHTING|DEPTH|VIEWPORT / LINE|LIGHTING|DEPTH|VIEWPORT", "decode(2929)=" + a + " decode(2932)=" + b);
        s.info("attribute bits", "0x0B71 = CURRENT 0x1 | POLYGON_STIPPLE 0x10 | PIXEL_MODE 0x20 | LIGHTING 0x40 | DEPTH_BUFFER 0x100 | ACCUM 0x200 | VIEWPORT 0x800 "
                + "(= 2929); 0x0B74 = LINE 0x4 | the same five (= 2932); both verified against the GL spec values");
    }

    private static boolean readCap(int cap) {
        return switch (cap) {
            case 3042 -> Draw.blendEnabled();
            case 3553 -> Draw.texturingEnabled();
            case 2896 -> Draw.lightingEnabled();
            case 3008 -> Draw.alphaTestEnabled();
            case 32826 -> Draw.rescaleNormalEnabled();
            case 2977 -> Draw.normalizeEnabled();
            case 2929 -> Draw.depthTestEnabled();
            case 2884 -> Draw.cullEnabled();
            case 2912 -> Draw.fogEnabled();
            case 2903 -> Draw.colorMaterialEnabledState();
            case 32823 -> Draw.polygonOffsetEnabled();
            default -> Draw.logicOpCursorEnabled();
        };
    }

    // ================================================================================================ 5. key, attribute stack

    private static void materialKeyAndAttribs(Report.Section s) {
        Draw.pushAttrib(1023);
        try {
            Draw.activeUnit(0);
            Draw.texturing(true);
            Draw.blend(true);
            Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
            Draw.alphaTest(true);
            Draw.alphaFunc(0.1f);
            Draw.depthTest(true);
            Draw.depthFunc(DepthFunc.LEQUAL);
            Draw.depthMask(false);
            Draw.cull(true);
            Draw.cullFace(Face.BACK);
            Draw.fog(true);
            long k = Draw.materialKey();
            boolean ok = MaterialKey.textured(k) && MaterialKey.blend(k) && MaterialKey.srcFactor(k) == BlendFactor.SRC_ALPHA.ordinal()
                    && MaterialKey.dstFactor(k) == BlendFactor.ONE_MINUS_SRC_ALPHA.ordinal() && MaterialKey.alphaMode(k) == 1 && MaterialKey.alphaRefIndex(k) == 0
                    && MaterialKey.depthTest(k) && MaterialKey.depthFunc(k) == DepthFunc.LEQUAL.ordinal() && !MaterialKey.depthWrite(k) && MaterialKey.cullBack(k)
                    && !MaterialKey.cullFront(k) && MaterialKey.fog(k) && MaterialKey.colorMask(k) == 7 && MaterialKey.program(k) == 1;
            s.check(ok, "Draw.materialKey packs the 1.7 layout", String.format("key 0x%x", k));
            RenderType t = Draw.currentRenderType(VertexFormat.UNIVERSAL);
            s.check(t.orderSensitive() && t == Draw.currentRenderType(VertexFormat.UNIVERSAL), "RenderType is interned and blend/no depth write is order sensitive", t.toString());
            Draw.depthMask(true);
            Draw.blend(false);
            s.check(!Draw.currentRenderType(VertexFormat.UNIVERSAL).orderSensitive(), "opaque LEQUAL depth-write state is not order sensitive", "still order sensitive");

            // attribute stack: DEPTH and CURRENT groups come back, others stay
            Draw.depthTest(true);
            Draw.depthFunc(DepthFunc.LEQUAL);
            Draw.color(0.1f, 0.2f, 0.3f, 0.4f);
            Draw.blendFunc(BlendFactor.ONE, BlendFactor.ONE);
            Draw.pushAttrib(AttribMask.decode(2929));
            Draw.depthTest(false);
            Draw.depthFunc(DepthFunc.GREATER);
            Draw.color(1f, 1f, 1f, 1f);
            Draw.blendFunc(BlendFactor.ZERO, BlendFactor.ZERO);
            Draw.popAttrib();
            boolean restored = Draw.depthTestEnabled() && Draw.depthFuncState() == DepthFunc.LEQUAL && Draw.colorR() == 0.1f && Draw.colorA() == 0.4f;
            boolean untouched = Draw.blendSrc() == BlendFactor.ZERO;
            s.check(restored && untouched, "pushAttrib/popAttrib restore exactly the selected groups", "restored=" + restored + " untouched=" + untouched);
            int dirty = Draw.dirtyLegacyGroups();
            s.check(dirty != 0, "setters accumulate dirty legacy groups", "dirtyLegacyGroups == 0");
        } finally {
            Draw.popAttrib();
        }
    }

    // ================================================================================================ 6. TessBridge

    private static final class ProbeThread extends MeshWorkerThread {
        volatile NativeTess seen;

        ProbeThread(NativeTess own) {
            super(() -> {
            }, "NR06-probe", own, 0);
        }

        @Override
        public void run() {
            seen = TessBridge.current();
        }
    }

    private static volatile Object sink;

    private static void tessBridge(Report.Section s) throws Exception {
        NativeTess own = TessBridge.createWorkerTess((raw, ints, vertices) -> {
        });
        ProbeThread t = new ProbeThread(own);
        t.start();
        t.join();
        s.check(t.seen == own && TessBridge.current() == TessBridge.renderThread(),
                "TessBridge.current(): worker thread -> its own NativeTess, other threads -> the render-thread instance", "wrong instance");
        int rounds = 100_000_000;
        for (int warm = 0; warm < 3; warm++) {
            run(2_000_000);
        }
        long t0 = System.nanoTime();
        Object last = run(rounds);
        long ns = System.nanoTime() - t0;
        sink = last;
        double perCall = ns / (double) rounds;
        s.check(perCall <= 2.0, String.format("TessBridge.current() microbenchmark %.3f ns/call (limit 2 ns)", perCall), String.format("%.3f ns/call", perCall));
    }

    private static Object run(int n) {
        NativeTess last = null;
        int same = 0;
        long acc = 0;
        final NativeTess render = TessBridge.renderThread();
        for (int i = 0; i < n; i++) {
            NativeTess t = TessBridge.current();
            same += t == render ? 1 : 0;
            acc += t.vertexCount() + i;
            last = t;
        }
        return same == n && acc != -1 ? last : null;
    }
}
