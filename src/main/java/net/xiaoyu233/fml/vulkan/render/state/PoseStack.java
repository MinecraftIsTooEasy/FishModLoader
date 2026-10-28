package net.xiaoyu233.fml.vulkan.render.state;

import net.xiaoyu233.fml.vulkan.VkLog;

/**
 * Matrix stack of the native renderer (1.4 / 5.3): column-major float matrices (element row r, column c at {@code m[c * 4 + r]}) with
 * exactly the arithmetic of the phase-1 {@code gl.state.MatrixStack} (rotation computed in double and stored as float, products in float
 * in the same evaluation order), so native and emulated draws compose bit for bit. Every level owns its {@code float[16]}; all methods
 * are allocation free after construction. Render thread only.
 */
public final class PoseStack {
    private final float[][] stack;
    private final float[] scratch = new float[16];
    private final float[] product = new float[16];
    private final int maxDepth;
    private int depth;

    /** Counter source of {@link #version()}; every top-matrix change takes a new value. */
    private int version;
    private int normalVersion = -1;
    private final float[] normal = new float[9];
    private int rescaleVersion = -1;
    private float rescale = 1f;
    private boolean warned;

    public PoseStack(int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth " + maxDepth);
        }
        this.maxDepth = maxDepth;
        this.stack = new float[maxDepth][16];
        reset();
    }

    /** The depth limit given to the constructor. */
    public int maxDepth() {
        return maxDepth;
    }

    /** Back to one identity level. */
    public void reset() {
        depth = 0;
        identity(stack[0]);
        version++;
    }

    private static void identity(float[] m) {
        for (int i = 0; i < 16; i++) {
            m[i] = 0f;
        }
        m[0] = m[5] = m[10] = m[15] = 1f;
    }

    /**
     * Duplicates the top level. Overflow is ignored (GL raises GL_STACK_OVERFLOW and keeps the stack), logged once.
     */
    public void push() {
        if (depth + 1 >= maxDepth) {
            warnOnce("overflow");
            return;
        }
        System.arraycopy(stack[depth], 0, stack[depth + 1], 0, 16);
        depth++;
    }

    /** Drops the top level. Underflow is ignored (GL raises GL_STACK_UNDERFLOW), logged once. */
    public void pop() {
        if (depth == 0) {
            warnOnce("underflow");
            return;
        }
        depth--;
        version++;
    }

    private void warnOnce(String what) {
        if (!warned) {
            warned = true;
            VkLog.LOGGER.warn("PoseStack {} (max depth {}); further occurrences are ignored silently", what, maxDepth);
        }
    }

    /** Number of levels on the stack (at least 1), like GL_*_STACK_DEPTH. */
    public int depth() {
        return depth + 1;
    }

    public void loadIdentity() {
        identity(stack[depth]);
        version++;
    }

    public void load(float[] m16) {
        System.arraycopy(m16, 0, stack[depth], 0, 16);
        version++;
    }

    /** {@code top = top * m} */
    public void mul(float[] m16) {
        final float[] t = stack[depth];
        multiply(t, m16, t);
        version++;
    }

    /** {@code out = a * b}; {@code out} may be {@code a} and/or {@code b}. Same term order as the phase-1 {@code Mat4.mul}. */
    private void multiply(float[] a, float[] b, float[] out) {
        final float[] t = product;
        for (int c = 0; c < 4; c++) {
            final int o = c * 4;
            final float b0 = b[o], b1 = b[o + 1], b2 = b[o + 2], b3 = b[o + 3];
            t[o] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3;
            t[o + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3;
            t[o + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3;
            t[o + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3;
        }
        System.arraycopy(t, 0, out, 0, 16);
    }

    public void translate(float x, float y, float z) {
        final float[] m = stack[depth];
        m[12] += m[0] * x + m[4] * y + m[8] * z;
        m[13] += m[1] * x + m[5] * y + m[9] * z;
        m[14] += m[2] * x + m[6] * y + m[10] * z;
        m[15] += m[3] * x + m[7] * y + m[11] * z;
        version++;
    }

    /** glTranslated: the arguments are narrowed to float first, like the GL facade does. */
    public void translate(double x, double y, double z) {
        translate((float) x, (float) y, (float) z);
    }

    public void scale(float x, float y, float z) {
        final float[] m = stack[depth];
        for (int i = 0; i < 4; i++) {
            m[i] *= x;
            m[4 + i] *= y;
            m[8 + i] *= z;
        }
        version++;
    }

    public void scale(double x, double y, double z) {
        scale((float) x, (float) y, (float) z);
    }

    /** glRotate: the axis is normalised, sin/cos are computed in double and stored as float; a zero axis is ignored. */
    public void rotate(float degrees, float ax, float ay, float az) {
        final double len = Math.sqrt((double) ax * ax + (double) ay * ay + (double) az * az);
        if (len < 1e-12) {
            return;
        }
        final double x = ax / len, y = ay / len, z = az / len;
        final double rad = Math.toRadians(degrees);
        final double c = Math.cos(rad), s = Math.sin(rad), t = 1.0 - c;
        final float[] r = scratch;
        r[0] = (float) (c + x * x * t);
        r[1] = (float) (y * x * t + z * s);
        r[2] = (float) (z * x * t - y * s);
        r[3] = 0f;
        r[4] = (float) (x * y * t - z * s);
        r[5] = (float) (c + y * y * t);
        r[6] = (float) (z * y * t + x * s);
        r[7] = 0f;
        r[8] = (float) (x * z * t + y * s);
        r[9] = (float) (y * z * t - x * s);
        r[10] = (float) (c + z * z * t);
        r[11] = 0f;
        r[12] = 0f;
        r[13] = 0f;
        r[14] = 0f;
        r[15] = 1f;
        mul(r);
    }

    /** glOrtho; a degenerate volume (GL_INVALID_VALUE) leaves the matrix unchanged. */
    public void ortho(double l, double r, double b, double t, double n, double f) {
        final double rl = r - l, tb = t - b, fn = f - n;
        if (rl == 0.0 || tb == 0.0 || fn == 0.0) {
            return;
        }
        final float[] m = scratch;
        identity(m);
        m[0] = (float) (2.0 / rl);
        m[5] = (float) (2.0 / tb);
        m[10] = (float) (-2.0 / fn);
        m[12] = (float) (-(r + l) / rl);
        m[13] = (float) (-(t + b) / tb);
        m[14] = (float) (-(f + n) / fn);
        mul(m);
    }

    /** glFrustum; near/far <= 0 or a degenerate volume (GL_INVALID_VALUE) leaves the matrix unchanged. */
    public void frustum(double l, double r, double b, double t, double n, double f) {
        final double rl = r - l, tb = t - b, fn = f - n;
        if (n <= 0.0 || f <= 0.0 || rl == 0.0 || tb == 0.0 || fn == 0.0) {
            return;
        }
        final float[] m = scratch;
        for (int i = 0; i < 16; i++) {
            m[i] = 0f;
        }
        m[0] = (float) (2.0 * n / rl);
        m[5] = (float) (2.0 * n / tb);
        m[8] = (float) ((r + l) / rl);
        m[9] = (float) ((t + b) / tb);
        m[10] = (float) (-(f + n) / fn);
        m[11] = -1f;
        m[14] = (float) (-2.0 * f * n / fn);
        mul(m);
    }

    /**
     * lwjgl_util 2.9.0 {@code Project.gluPerspective} (read from its bytecode): float arithmetic throughout, sin/cos of the half angle
     * through {@code double}, the matrix multiplied onto the top level; a degenerate argument set returns without change.
     */
    public void perspective(float fovyDeg, float aspect, float zNear, float zFar) {
        final float radians = fovyDeg / 2.0f * 3.1415927f / 180.0f;
        final float deltaZ = zFar - zNear;
        final float sine = (float) Math.sin((double) radians);
        if (deltaZ == 0.0f || sine == 0.0f || aspect == 0.0f) {
            return;
        }
        final float cotangent = (float) Math.cos((double) radians) / sine;
        final float[] m = scratch;
        identity(m);
        m[0] = cotangent / aspect;
        m[5] = cotangent;
        m[10] = -(zFar + zNear) / deltaZ;
        m[11] = -1.0f;
        m[14] = -2.0f * zNear * zFar / deltaZ;
        m[15] = 0.0f;
        mul(m);
    }

    /** The live column-major array of the top level; read-only for callers. */
    public float[] top() {
        return stack[depth];
    }

    public void copyTop(java.nio.FloatBuffer dst) {
        final float[] m = stack[depth];
        final int p = dst.position();
        for (int i = 0; i < 16; i++) {
            dst.put(p + i, m[i]);
        }
    }

    public void copyTop(float[] dst, int offset) {
        System.arraycopy(stack[depth], 0, dst, offset, 16);
    }

    /** Takes a new value on every change of the top matrix (a push alone does not change it). */
    public int version() {
        return version;
    }

    private int identityVersion = -1;
    private boolean identity;

    /** True when the top matrix is exactly the identity (cached by {@link #version()}). */
    public boolean isIdentity() {
        if (identityVersion != version) {
            final float[] m = stack[depth];
            boolean id = true;
            for (int i = 0; i < 16 && id; i++) {
                id = m[i] == ((i & 3) == (i >> 2) ? 1f : 0f);
            }
            identity = id;
            identityVersion = version;
        }
        return identity;
    }

    /**
     * Inverse transpose of the upper 3x3 of {@link #top()} (double precision, stored as float), column-major: a normal transforms as
     * {@code n'_r = sum_c N[c*3 + r] * n_c}. Cached by {@link #version()}; a singular matrix yields the identity. The array is reused.
     */
    public float[] normalMatrix() {
        if (normalVersion != version) {
            computeInverse3(stack[depth], normal, true);
            normalVersion = version;
        }
        return normal;
    }

    /**
     * GL_RESCALE_NORMAL factor: 1 / length of the third row of the inverse upper 3x3 of the modelview (for a uniform scale s it is s,
     * so {@code f * n'} restores a unit normal). Cached by {@link #version()}.
     */
    public float rescaleFactor() {
        if (rescaleVersion != version) {
            // third row of the inverse (row index 2) = elements [2], [5], [8] of the column-major inverse
            final float[] inv = invScratch;
            computeInverse3(stack[depth], inv, false);
            final double a = inv[2], b = inv[5], c = inv[8];
            final double len = Math.sqrt(a * a + b * b + c * c);
            rescale = len > 1e-30 ? (float) (1.0 / len) : 1f;
            rescaleVersion = version;
        }
        return rescale;
    }

    private final float[] invScratch = new float[9];

    /** Inverse of the upper 3x3 of {@code m} into {@code out} (column-major), optionally transposed; identity when singular. */
    private static void computeInverse3(float[] m, float[] out, boolean transpose) {
        final double a00 = m[0], a10 = m[1], a20 = m[2];
        final double a01 = m[4], a11 = m[5], a21 = m[6];
        final double a02 = m[8], a12 = m[9], a22 = m[10];
        // cofactors (row r, column c of the inverse = cofactor(c, r) / det)
        final double c00 = a11 * a22 - a12 * a21;
        final double c01 = a12 * a20 - a10 * a22;
        final double c02 = a10 * a21 - a11 * a20;
        final double det = a00 * c00 + a01 * c01 + a02 * c02;
        if (Math.abs(det) < 1e-30) {
            for (int i = 0; i < 9; i++) {
                out[i] = 0f;
            }
            out[0] = out[4] = out[8] = 1f;
            return;
        }
        final double id = 1.0 / det;
        // inverse in row/column terms: inv[r][c]
        final double i00 = c00 * id;
        final double i01 = (a02 * a21 - a01 * a22) * id;
        final double i02 = (a01 * a12 - a02 * a11) * id;
        final double i10 = c01 * id;
        final double i11 = (a00 * a22 - a02 * a20) * id;
        final double i12 = (a02 * a10 - a00 * a12) * id;
        final double i20 = c02 * id;
        final double i21 = (a01 * a20 - a00 * a21) * id;
        final double i22 = (a00 * a11 - a01 * a10) * id;
        if (transpose) {
            // out[c*3 + r] = inv[c][r]
            out[0] = (float) i00;
            out[1] = (float) i01;
            out[2] = (float) i02;
            out[3] = (float) i10;
            out[4] = (float) i11;
            out[5] = (float) i12;
            out[6] = (float) i20;
            out[7] = (float) i21;
            out[8] = (float) i22;
        } else {
            // out[c*3 + r] = inv[r][c]
            out[0] = (float) i00;
            out[1] = (float) i10;
            out[2] = (float) i20;
            out[3] = (float) i01;
            out[4] = (float) i11;
            out[5] = (float) i21;
            out[6] = (float) i02;
            out[7] = (float) i12;
            out[8] = (float) i22;
        }
    }
}
