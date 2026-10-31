package net.xiaoyu233.fml.vulkan.gl.state;

/**
 * Per texture unit state: texture environment, texture coordinate generation and the per-unit enable bits. Only unit 0 and
 * unit 1 feed the fixed-function shader (see ShaderInterface); the others are tracked so glGet* and glPush/PopAttrib behave.
 */
final class TexUnitState {
    /** {@link #targetEnable} bits. */
    static final int TARGET_1D = 1, TARGET_2D = 2, TARGET_3D = 4, TARGET_CUBE = 8;
    /** {@link #texGenEnable} bits, coordinate index c = 0..3 (S, T, R, Q) uses bit (1 << c). */

    int envMode;
    final float[] envColor = new float[4];
    int combineRgb;
    int combineAlpha;
    final int[] sourceRgb = new int[3];
    final int[] sourceAlpha = new int[3];
    final int[] operandRgb = new int[3];
    final int[] operandAlpha = new int[3];
    float rgbScale;
    float alphaScale;
    float lodBias;
    boolean coordReplace;

    int targetEnable;
    int texGenEnable;
    /** GL_EYE_LINEAR, GL_OBJECT_LINEAR, GL_SPHERE_MAP, ... per coordinate S, T, R, Q. */
    final int[] genMode = new int[4];
    final float[][] objectPlane = new float[4][4];
    /** Stored already multiplied by the inverse modelview of the glTexGen call (GL semantic), as the shader expects. */
    final float[][] eyePlane = new float[4][4];

    TexUnitState() {
        reset();
    }

    void reset() {
        envMode = GLConst.GL_MODULATE;
        LightState.set4(envColor, 0f, 0f, 0f, 0f);
        combineRgb = GLConst.GL_MODULATE;
        combineAlpha = GLConst.GL_MODULATE;
        sourceRgb[0] = GLConst.GL_TEXTURE;
        sourceRgb[1] = GLConst.GL_PREVIOUS;
        sourceRgb[2] = GLConst.GL_CONSTANT;
        sourceAlpha[0] = GLConst.GL_TEXTURE;
        sourceAlpha[1] = GLConst.GL_PREVIOUS;
        sourceAlpha[2] = GLConst.GL_CONSTANT;
        operandRgb[0] = GLConst.GL_SRC_COLOR;
        operandRgb[1] = GLConst.GL_SRC_COLOR;
        operandRgb[2] = GLConst.GL_SRC_ALPHA;
        operandAlpha[0] = GLConst.GL_SRC_ALPHA;
        operandAlpha[1] = GLConst.GL_SRC_ALPHA;
        operandAlpha[2] = GLConst.GL_SRC_ALPHA;
        rgbScale = 1f;
        alphaScale = 1f;
        lodBias = 0f;
        coordReplace = false;
        targetEnable = 0;
        texGenEnable = 0;
        for (int c = 0; c < 4; c++) {
            genMode[c] = GLConst.GL_EYE_LINEAR;
            LightState.set4(objectPlane[c], c == 0 ? 1f : 0f, c == 1 ? 1f : 0f, 0f, 0f);
            LightState.set4(eyePlane[c], c == 0 ? 1f : 0f, c == 1 ? 1f : 0f, 0f, 0f);
        }
    }

    void copyFrom(TexUnitState o) {
        envMode = o.envMode;
        System.arraycopy(o.envColor, 0, envColor, 0, 4);
        combineRgb = o.combineRgb;
        combineAlpha = o.combineAlpha;
        System.arraycopy(o.sourceRgb, 0, sourceRgb, 0, 3);
        System.arraycopy(o.sourceAlpha, 0, sourceAlpha, 0, 3);
        System.arraycopy(o.operandRgb, 0, operandRgb, 0, 3);
        System.arraycopy(o.operandAlpha, 0, operandAlpha, 0, 3);
        rgbScale = o.rgbScale;
        alphaScale = o.alphaScale;
        lodBias = o.lodBias;
        coordReplace = o.coordReplace;
        targetEnable = o.targetEnable;
        texGenEnable = o.texGenEnable;
        System.arraycopy(o.genMode, 0, genMode, 0, 4);
        for (int c = 0; c < 4; c++) {
            System.arraycopy(o.objectPlane[c], 0, objectPlane[c], 0, 4);
            System.arraycopy(o.eyePlane[c], 0, eyePlane[c], 0, 4);
        }
    }
}
