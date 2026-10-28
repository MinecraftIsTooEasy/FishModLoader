package net.xiaoyu233.fml.vulkan.render.state;

/**
 * Table of alpha-test reference values addressed by bits 10-17 of the material key (1.7): 0.1, 1/255, 0.01, 0.001 ; every other value (e.g. the dragon death dissolve)
 * uses alpha mode 2 and is never added. Render thread only; entries are never removed, so indices stay valid for the life of
 * the process.
 */
public final class AlphaRefs {
    public static final int CAPACITY = 256;

    private static final float[] VALUES = new float[CAPACITY];
    private static int count;

    static {
        VALUES[count++] = 0.1f;
        VALUES[count++] = 1.0f / 255.0f;
        VALUES[count++] = 0.01f;
        VALUES[count++] = 0.001f;
    }

    private AlphaRefs() {
    }

    /** Index of one of the four fixed seed values (bit-exact match), else -1: the caller uses the dynamic alpha mode 2 (value in push constants). */
    public static int index(float ref) {
        final int bits = Float.floatToIntBits(ref);
        for (int i = 0; i < count; i++) {
            if (Float.floatToIntBits(VALUES[i]) == bits) {
                return i;
            }
        }
        return -1;
    }

    /** The reference value of an index returned by {@link #index(float)}. */
    public static float value(int index) {
        return VALUES[index];
    }

    public static int size() {
        return count;
    }
}
