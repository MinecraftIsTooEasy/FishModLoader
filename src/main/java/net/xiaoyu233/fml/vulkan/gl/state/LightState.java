package net.xiaoyu233.fml.vulkan.gl.state;

/** One GL light. Position and spot direction are stored in eye space (transformed at glLight time). */
final class LightState {
    final float[] ambient = new float[4];
    final float[] diffuse = new float[4];
    final float[] specular = new float[4];
    final float[] position = new float[4];
    final float[] spotDirection = new float[3];
    float spotExponent;
    float spotCutoff;
    float constantAttenuation;
    float linearAttenuation;
    float quadraticAttenuation;

    /** GL initial state: light 0 is white, the others black; every light sits at (0, 0, 1, 0) looking down -z. */
    void reset(int index) {
        set4(ambient, 0f, 0f, 0f, 1f);
        float c = index == 0 ? 1f : 0f;
        set4(diffuse, c, c, c, 1f);
        set4(specular, c, c, c, 1f);
        set4(position, 0f, 0f, 1f, 0f);
        spotDirection[0] = 0f;
        spotDirection[1] = 0f;
        spotDirection[2] = -1f;
        spotExponent = 0f;
        spotCutoff = 180f;
        constantAttenuation = 1f;
        linearAttenuation = 0f;
        quadraticAttenuation = 0f;
    }

    void copyFrom(LightState o) {
        System.arraycopy(o.ambient, 0, ambient, 0, 4);
        System.arraycopy(o.diffuse, 0, diffuse, 0, 4);
        System.arraycopy(o.specular, 0, specular, 0, 4);
        System.arraycopy(o.position, 0, position, 0, 4);
        System.arraycopy(o.spotDirection, 0, spotDirection, 0, 3);
        spotExponent = o.spotExponent;
        spotCutoff = o.spotCutoff;
        constantAttenuation = o.constantAttenuation;
        linearAttenuation = o.linearAttenuation;
        quadraticAttenuation = o.quadraticAttenuation;
    }

    static void set4(float[] a, float x, float y, float z, float w) {
        a[0] = x;
        a[1] = y;
        a[2] = z;
        a[3] = w;
    }
}
