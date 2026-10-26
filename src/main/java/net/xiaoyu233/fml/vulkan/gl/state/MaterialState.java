package net.xiaoyu233.fml.vulkan.gl.state;

/** GL material parameters of one face. */
final class MaterialState {
    final float[] ambient = new float[4];
    final float[] diffuse = new float[4];
    final float[] specular = new float[4];
    final float[] emission = new float[4];
    float shininess;

    void reset() {
        LightState.set4(ambient, 0.2f, 0.2f, 0.2f, 1f);
        LightState.set4(diffuse, 0.8f, 0.8f, 0.8f, 1f);
        LightState.set4(specular, 0f, 0f, 0f, 1f);
        LightState.set4(emission, 0f, 0f, 0f, 1f);
        shininess = 0f;
    }

    void copyFrom(MaterialState o) {
        System.arraycopy(o.ambient, 0, ambient, 0, 4);
        System.arraycopy(o.diffuse, 0, diffuse, 0, 4);
        System.arraycopy(o.specular, 0, specular, 0, 4);
        System.arraycopy(o.emission, 0, emission, 0, 4);
        shininess = o.shininess;
    }
}
