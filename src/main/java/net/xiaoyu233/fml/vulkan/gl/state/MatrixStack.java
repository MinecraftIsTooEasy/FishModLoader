package net.xiaoyu233.fml.vulkan.gl.state;

/**
 * GL matrix stack (modelview 64 deep, projection 32, texture 32 per unit). Every level is its own {@code float[16]}
 * (column-major, GL order), so {@link #top()} is the live matrix of the current level and can be handed out and read directly.
 * Allocation free after construction.
 */
final class MatrixStack {
    private final float[][] stack;
    private final float[] scratch = new float[16];
    private int depth;

    MatrixStack(int maxDepth) {
        stack = new float[maxDepth][16];
        reset();
    }

    void reset() {
        depth = 0;
        Mat4.identity(stack[0]);
    }

    /** The live matrix of the current stack level (16 floats, column-major). */
    float[] top() {
        return stack[depth];
    }

    /** Number of levels currently on the stack (at least 1), like GL_*_STACK_DEPTH. */
    int depth() {
        return depth + 1;
    }

    int maxDepth() {
        return stack.length;
    }

    /** Returns false on overflow (caller raises GL_STACK_OVERFLOW). */
    boolean push() {
        if (depth + 1 >= stack.length) {
            return false;
        }
        System.arraycopy(stack[depth], 0, stack[depth + 1], 0, 16);
        depth++;
        return true;
    }

    /** Returns false on underflow (caller raises GL_STACK_UNDERFLOW). */
    boolean pop() {
        if (depth == 0) {
            return false;
        }
        depth--;
        return true;
    }

    void loadIdentity() {
        Mat4.identity(stack[depth]);
    }

    void load(float[] m) {
        System.arraycopy(m, 0, stack[depth], 0, 16);
    }

    /** {@code top = top * m} */
    void mult(float[] m) {
        final float[] t = stack[depth];
        Mat4.mul(t, m, t);
    }

    void translate(float x, float y, float z) {
        final float[] m = stack[depth];
        m[12] += m[0] * x + m[4] * y + m[8] * z;
        m[13] += m[1] * x + m[5] * y + m[9] * z;
        m[14] += m[2] * x + m[6] * y + m[10] * z;
        m[15] += m[3] * x + m[7] * y + m[11] * z;
    }

    void scale(float x, float y, float z) {
        final float[] m = stack[depth];
        for (int i = 0; i < 4; i++) {
            m[i] *= x;
            m[4 + i] *= y;
            m[8 + i] *= z;
        }
    }

    /** glRotate: the axis is normalized, a zero axis is ignored. */
    void rotate(float angleDeg, float ax, float ay, float az) {
        final double len = Math.sqrt((double) ax * ax + (double) ay * ay + (double) az * az);
        if (len < 1e-12) {
            return;
        }
        final double x = ax / len, y = ay / len, z = az / len;
        final double rad = Math.toRadians(angleDeg);
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
        mult(r);
    }

    /** glOrtho; returns false (GL_INVALID_VALUE) for a degenerate volume. */
    boolean ortho(double left, double right, double bottom, double top, double zNear, double zFar) {
        final double rl = right - left, tb = top - bottom, fn = zFar - zNear;
        if (rl == 0.0 || tb == 0.0 || fn == 0.0) {
            return false;
        }
        final float[] m = scratch;
        Mat4.identity(m);
        m[0] = (float) (2.0 / rl);
        m[5] = (float) (2.0 / tb);
        m[10] = (float) (-2.0 / fn);
        m[12] = (float) (-(right + left) / rl);
        m[13] = (float) (-(top + bottom) / tb);
        m[14] = (float) (-(zFar + zNear) / fn);
        mult(m);
        return true;
    }

    /** glFrustum; returns false (GL_INVALID_VALUE) for near/far <= 0 or a degenerate volume. */
    boolean frustum(double left, double right, double bottom, double top, double zNear, double zFar) {
        final double rl = right - left, tb = top - bottom, fn = zFar - zNear;
        if (zNear <= 0.0 || zFar <= 0.0 || rl == 0.0 || tb == 0.0 || fn == 0.0) {
            return false;
        }
        final float[] m = scratch;
        for (int i = 0; i < 16; i++) {
            m[i] = 0f;
        }
        m[0] = (float) (2.0 * zNear / rl);
        m[5] = (float) (2.0 * zNear / tb);
        m[8] = (float) ((right + left) / rl);
        m[9] = (float) ((top + bottom) / tb);
        m[10] = (float) (-(zFar + zNear) / fn);
        m[11] = -1f;
        m[14] = (float) (-2.0 * zFar * zNear / fn);
        mult(m);
        return true;
    }
}
