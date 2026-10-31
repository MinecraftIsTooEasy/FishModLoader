package net.xiaoyu233.fml.vulkan.gl.state;

/**
 * Allocation free 4x4 matrix helpers. All matrices are {@code float[16]} in column-major (GL) order: element (row r, column c)
 * lives at {@code m[c * 4 + r]}. Render thread only (the helpers share static scratch arrays).
 */
final class Mat4 {
    private static final float[] TMP = new float[16];
    private static final double[] INV = new double[16];

    private Mat4() {
    }

    static void identity(float[] m) {
        for (int i = 0; i < 16; i++) {
            m[i] = 0f;
        }
        m[0] = m[5] = m[10] = m[15] = 1f;
    }

    /** {@code out = a * b}; {@code out} may be the same array as {@code a} and/or {@code b}. */
    static void mul(float[] a, float[] b, float[] out) {
        final float[] t = TMP;
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

    /** Transposes {@code m} in place. */
    static void transpose(float[] m) {
        float t;
        t = m[1]; m[1] = m[4]; m[4] = t;
        t = m[2]; m[2] = m[8]; m[8] = t;
        t = m[3]; m[3] = m[12]; m[12] = t;
        t = m[6]; m[6] = m[9]; m[9] = t;
        t = m[7]; m[7] = m[13]; m[13] = t;
        t = m[11]; m[11] = m[14]; m[14] = t;
    }

    /**
     * {@code out = inverse(m)} (general 4x4 inverse, computed in double precision). Returns false and leaves {@code out}
     * untouched when {@code m} is singular. {@code out} may be the same array as {@code m}.
     */
    static boolean invert(float[] m, float[] out) {
        final double[] inv = INV;
        final double m0 = m[0], m1 = m[1], m2 = m[2], m3 = m[3], m4 = m[4], m5 = m[5], m6 = m[6], m7 = m[7];
        final double m8 = m[8], m9 = m[9], m10 = m[10], m11 = m[11], m12 = m[12], m13 = m[13], m14 = m[14], m15 = m[15];

        inv[0] = m5 * m10 * m15 - m5 * m11 * m14 - m9 * m6 * m15 + m9 * m7 * m14 + m13 * m6 * m11 - m13 * m7 * m10;
        inv[4] = -m4 * m10 * m15 + m4 * m11 * m14 + m8 * m6 * m15 - m8 * m7 * m14 - m12 * m6 * m11 + m12 * m7 * m10;
        inv[8] = m4 * m9 * m15 - m4 * m11 * m13 - m8 * m5 * m15 + m8 * m7 * m13 + m12 * m5 * m11 - m12 * m7 * m9;
        inv[12] = -m4 * m9 * m14 + m4 * m10 * m13 + m8 * m5 * m14 - m8 * m6 * m13 - m12 * m5 * m10 + m12 * m6 * m9;
        inv[1] = -m1 * m10 * m15 + m1 * m11 * m14 + m9 * m2 * m15 - m9 * m3 * m14 - m13 * m2 * m11 + m13 * m3 * m10;
        inv[5] = m0 * m10 * m15 - m0 * m11 * m14 - m8 * m2 * m15 + m8 * m3 * m14 + m12 * m2 * m11 - m12 * m3 * m10;
        inv[9] = -m0 * m9 * m15 + m0 * m11 * m13 + m8 * m1 * m15 - m8 * m3 * m13 - m12 * m1 * m11 + m12 * m3 * m9;
        inv[13] = m0 * m9 * m14 - m0 * m10 * m13 - m8 * m1 * m14 + m8 * m2 * m13 + m12 * m1 * m10 - m12 * m2 * m9;
        inv[2] = m1 * m6 * m15 - m1 * m7 * m14 - m5 * m2 * m15 + m5 * m3 * m14 + m13 * m2 * m7 - m13 * m3 * m6;
        inv[6] = -m0 * m6 * m15 + m0 * m7 * m14 + m4 * m2 * m15 - m4 * m3 * m14 - m12 * m2 * m7 + m12 * m3 * m6;
        inv[10] = m0 * m5 * m15 - m0 * m7 * m13 - m4 * m1 * m15 + m4 * m3 * m13 + m12 * m1 * m7 - m12 * m3 * m5;
        inv[14] = -m0 * m5 * m14 + m0 * m6 * m13 + m4 * m1 * m14 - m4 * m2 * m13 - m12 * m1 * m6 + m12 * m2 * m5;
        inv[3] = -m1 * m6 * m11 + m1 * m7 * m10 + m5 * m2 * m11 - m5 * m3 * m10 - m9 * m2 * m7 + m9 * m3 * m6;
        inv[7] = m0 * m6 * m11 - m0 * m7 * m10 - m4 * m2 * m11 + m4 * m3 * m10 + m8 * m2 * m7 - m8 * m3 * m6;
        inv[11] = -m0 * m5 * m11 + m0 * m7 * m9 + m4 * m1 * m11 - m4 * m3 * m9 - m8 * m1 * m7 + m8 * m3 * m5;
        inv[15] = m0 * m5 * m10 - m0 * m6 * m9 - m4 * m1 * m10 + m4 * m2 * m9 + m8 * m1 * m6 - m8 * m2 * m5;

        final double det = m0 * inv[0] + m1 * inv[4] + m2 * inv[8] + m3 * inv[12];
        if (det == 0.0 || Double.isNaN(det) || Math.abs(det) < 1e-30) {
            return false;
        }
        final double s = 1.0 / det;
        for (int i = 0; i < 16; i++) {
            out[i] = (float) (inv[i] * s);
        }
        return true;
    }
}
