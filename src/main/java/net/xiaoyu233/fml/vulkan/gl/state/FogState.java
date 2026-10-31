package net.xiaoyu233.fml.vulkan.gl.state;

/** GL fog parameters (the GL_FOG enable bit lives in GLState's capability set). */
final class FogState {
    int mode;
    float density;
    float start;
    float end;
    float index;
    final float[] color = new float[4];
    /** GL_NV_fog_distance: GL_EYE_PLANE_ABSOLUTE_NV / GL_EYE_PLANE / GL_EYE_RADIAL_NV. */
    int distanceMode;

    void reset() {
        mode = GLConst.GL_EXP;
        density = 1f;
        start = 0f;
        end = 1f;
        index = 0f;
        LightState.set4(color, 0f, 0f, 0f, 0f);
        distanceMode = GLConst.GL_EYE_PLANE_ABSOLUTE_NV;
    }

    void copyFrom(FogState o) {
        mode = o.mode;
        density = o.density;
        start = o.start;
        end = o.end;
        index = o.index;
        System.arraycopy(o.color, 0, color, 0, 4);
        distanceMode = o.distanceMode;
    }
}
