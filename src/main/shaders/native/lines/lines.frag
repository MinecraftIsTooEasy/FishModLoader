#version 450
#extension GL_GOOGLE_include_directive : require
#include "../include/fragment.glsl"

layout(location = 0) in vec4 vColor;
layout(location = 4) in vec3 vEye;

layout(location = 0) out vec4 outColor;

void main() {
    vec4 c = vColor;
    if (FOG) {
        c.rgb = mix(pu.fogColor.rgb, c.rgb, fogFactor(vEye));
    }
    outColor = c;
}
