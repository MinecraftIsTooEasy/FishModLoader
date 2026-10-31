#version 450
#extension GL_GOOGLE_include_directive : require
#include "../include/fragment.glsl"

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec4 vTex;
layout(location = 2) in vec3 vEye;

layout(location = 0) out vec4 outColor;

void main() {
    vec4 c = vColor;
    if (TEXTURED) {
        // GL 1.5 projective lookup: (s/q, t/q); r is ignored for 2D textures.
        c *= textureProj(uTexture, vec3(vTex.xy, vTex.w));
    }
    if (DEPTH_ONLY) {
        outColor = vec4(0.0);
        return;
    }
    if (FOG) {
        c.rgb = mix(pu.fogColor.rgb, c.rgb, fogFactor(vEye));
    }
    outColor = c;
}
