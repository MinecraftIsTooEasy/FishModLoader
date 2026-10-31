package net.xiaoyu233.fml.vulkan.render.state;

/**
 * {@code RenderHelper} semantics of the native renderer (1.10), evaluated on the CPU per vertex (GL fixed function lighting was per
 * vertex too, so lit and unlit geometry can share a batch). Two directional lights, light-model ambient 0.4, light diffuse 0.6, light
 * ambient 0, no specular, no attenuation, no emission. The light directions are transformed by the modelview current at the call
 * ({@link #enableStandard()}), exactly like {@code glLight(GL_POSITION)}. Render thread only.
 */
public final class Lighting {
    private static final float MODEL_AMBIENT_STANDARD = 0.4f;
    private static final float MODEL_AMBIENT_GL_DEFAULT = 0.2f;
    private static final float LIGHT_DIFFUSE = 0.6f;
    private static final float MATERIAL_AMBIENT_DEFAULT = 0.2f;
    private static final float MATERIAL_DIFFUSE_DEFAULT = 0.8f;

    /** {@code Vec3.createVectorHelper(0.2F, 1.0, -0.7F).normalize()} of MITE's RenderHelper (float literal widened to double, float sqrt). */
    private static final float[] WORLD_DIR0 = normalized(0.2f, 1.0, -0.7f);
    private static final float[] WORLD_DIR1 = normalized(-0.2f, 1.0, 0.7f);

    private static float modelAmbient = MODEL_AMBIENT_GL_DEFAULT;

    private Lighting() {
    }

    /** MITE {@code Vec3.normalize}: sqrt through float, components divided in double, stored as float. */
    private static float[] normalized(double x, double y, double z) {
        double len = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[] {(float) (x / len), (float) (y / len), (float) (z / len)};
    }

    /** Lights 0 and 1 on, directions = fixed world directions x current pose (eye space), colour material AMBIENT_AND_DIFFUSE, flat shade. */
    public static void enableStandard() {
        Draw.lighting(true);
        Draw.light0 = true;
        Draw.light1 = true;
        Draw.colorMaterialEnabled(true);
        Draw.colorMaterial(ColorMaterial.AMBIENT_AND_DIFFUSE);
        transformDirection(WORLD_DIR0, Draw.LIGHT_DIR0);
        transformDirection(WORLD_DIR1, Draw.LIGHT_DIR1);
        Draw.smoothShading(false);
        modelAmbient = MODEL_AMBIENT_STANDARD;
        Draw.touchLighting();
    }

    /** {@code push, rotate(-30, Y), rotate(165, X), enableStandard, pop}: the item-in-GUI lighting. */
    public static void enableGui() {
        PoseStack pose = Draw.pose();
        pose.push();
        pose.rotate(-30.0f, 0.0f, 1.0f, 0.0f);
        pose.rotate(165.0f, 1.0f, 0.0f, 0.0f);
        enableStandard();
        pose.pop();
    }

    /** Lighting, both lights and colour material off. */
    public static void disable() {
        Draw.lighting(false);
        Draw.light0 = false;
        Draw.light1 = false;
        Draw.colorMaterialEnabled(false);
        Draw.touchLighting();
    }

    /** For the attribute stack of {@link Draw}: restores the light-model ambient saved with the LIGHTING group. */
    static void setModelAmbient(float value) {
        modelAmbient = value;
    }

    /** Back to the GL defaults (light-model ambient 0.2); called by {@link Draw#resetToGlDefaults()}. */
    static void reset() {
        modelAmbient = MODEL_AMBIENT_GL_DEFAULT;
    }

    /** eye = upper 3x3 of the current modelview x dir (w = 0), then normalised in double precision (GL normalises the light vector). */
    private static void transformDirection(float[] dir, float[] out) {
        final float[] m = Draw.pose().top();
        final double x = m[0] * dir[0] + m[4] * dir[1] + m[8] * dir[2];
        final double y = m[1] * dir[0] + m[5] * dir[1] + m[9] * dir[2];
        final double z = m[2] * dir[0] + m[6] * dir[1] + m[10] * dir[2];
        final double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-30) {
            out[0] = out[1] = out[2] = 0f;
            return;
        }
        out[0] = (float) (x / len);
        out[1] = (float) (y / len);
        out[2] = (float) (z / len);
    }

    /**
     * Lit colour of {@code abgr} for the eye-space normal ({@code nx, ny, nz} already transformed and, if the state asks for it,
     * normalised or rescaled): {@code rgb = clamp(ambientMaterial * 0.4 + diffuseMaterial * 0.6 * (max(n.L0,0) + max(n.L1,0)))}. With
     * the colour material AMBIENT_AND_DIFFUSE both materials are the vertex colour and alpha is kept; with AMBIENT the diffuse material is
     * the GL default (0.8) and alpha is 1; with DIFFUSE the ambient material is the GL default (0.2); with colour material off both are
     * defaults. Returns the packed lit ABGR.
     */
    public static int lit(int abgr, float nx, float ny, float nz) {
        float d = 0f;
        if (Draw.light0) {
            final float[] l = Draw.LIGHT_DIR0;
            final float dot = nx * l[0] + ny * l[1] + nz * l[2];
            if (dot > 0f) {
                d += dot;
            }
        }
        if (Draw.light1) {
            final float[] l = Draw.LIGHT_DIR1;
            final float dot = nx * l[0] + ny * l[1] + nz * l[2];
            if (dot > 0f) {
                d += dot;
            }
        }
        final boolean cm = Draw.colorMaterialEnabledState();
        final ColorMaterial mode = Draw.colorMaterialMode();
        final boolean ambientFromColor = cm && mode != ColorMaterial.DIFFUSE;
        final boolean diffuseFromColor = cm && mode != ColorMaterial.AMBIENT;
        final float diff = LIGHT_DIFFUSE * d;
        final float r = channel(abgr & 0xFF, ambientFromColor, diffuseFromColor, diff);
        final float g = channel(abgr >>> 8 & 0xFF, ambientFromColor, diffuseFromColor, diff);
        final float b = channel(abgr >>> 16 & 0xFF, ambientFromColor, diffuseFromColor, diff);
        final int a = diffuseFromColor ? abgr >>> 24 : 255;
        return a << 24 | toByte(b) << 16 | toByte(g) << 8 | toByte(r);
    }

    private static float channel(int c, boolean ambientFromColor, boolean diffuseFromColor, float diff) {
        final float cf = c * (1f / 255f);
        final float ambient = (ambientFromColor ? cf : MATERIAL_AMBIENT_DEFAULT) * modelAmbient;
        final float diffuse = (diffuseFromColor ? cf : MATERIAL_DIFFUSE_DEFAULT) * diff;
        return ambient + diffuse;
    }

    private static int toByte(float v) {
        final int i = (int) (v * 255f + 0.5f);
        return i < 0 ? 0 : Math.min(i, 255);
    }

    /**
     * Same as {@link #lit} for a normal in object space: transformed by the normal matrix of the pose, then normalised
     * ({@code GL_NORMALIZE}) or scaled by the rescale factor ({@code GL_RESCALE_NORMAL}) according to the {@link Draw} state; neither
     * leaves the transformed length as is (an arrow normal of length 0.05625 stays short, as in GL).
     */
    public static int litObjectNormal(int abgr, float ox, float oy, float oz) {
        final PoseStack pose = Draw.pose();
        final float[] n = pose.normalMatrix();
        float nx = n[0] * ox + n[3] * oy + n[6] * oz;
        float ny = n[1] * ox + n[4] * oy + n[7] * oz;
        float nz = n[2] * ox + n[5] * oy + n[8] * oz;
        if (Draw.normalizeEnabled()) {
            final double len = Math.sqrt((double) nx * nx + (double) ny * ny + (double) nz * nz);
            if (len > 1e-30) {
                final float inv = (float) (1.0 / len);
                nx *= inv;
                ny *= inv;
                nz *= inv;
            }
        } else if (Draw.rescaleNormalEnabled()) {
            final float f = pose.rescaleFactor();
            nx *= f;
            ny *= f;
            nz *= f;
        }
        return lit(abgr, nx, ny, nz);
    }

    /** Eye-space direction of light 0 or 1 (live array, read-only). */
    public static float[] lightDir(int index) {
        return index == 0 ? Draw.LIGHT_DIR0 : Draw.LIGHT_DIR1;
    }

    public static boolean lightEnabled(int index) {
        return index == 0 ? Draw.light0 : Draw.light1;
    }

    /** The light-model ambient intensity (0.4 after {@link #enableStandard()}, GL default 0.2 before). */
    public static float modelAmbient() {
        return modelAmbient;
    }
}
