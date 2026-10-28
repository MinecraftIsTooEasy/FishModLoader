#version 450
#extension GL_GOOGLE_include_directive : require
#include "../include/common.glsl"

// TERRAIN vertex format (28 bytes, section local) + per-section instance offset (camera relative, computed in double on the CPU).
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inUv;
layout(location = 3) in uvec2 inLight;      // block << 4, sky << 4
layout(location = 4) in vec3 inSection;     // instance: sectionOrigin - camera

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vUv;
layout(location = 2) out vec2 vLight;
layout(location = 3) out vec4 vOverlay;
layout(location = 4) out vec3 vEye;

void main() {
    // MITE: translate(origin) . translate(8) . scale(1.00001) . translate(-8), i.e. p * s - 8 * (s - 1)
    vec3 world = inPos * pu.terrainParams.x - pu.terrainParams.y + inSection;
    vec3 eye = (pu.terrainView * vec4(world, 1.0)).xyz;
    gl_Position = toVulkanClip(pu.projection * vec4(eye, 1.0));
    vColor = inColor;
    vUv = inUv;
    vLight = vec2(inLight);
    vOverlay = vec4(0.0);
    vEye = eye;
}
