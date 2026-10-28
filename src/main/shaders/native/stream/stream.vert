#version 450
#extension GL_GOOGLE_include_directive : require
#include "../include/common.glsl"

// UNIVERSAL vertex format (32 bytes): positions are already in eye space, colours already lit (CPU side, NativeTess / VertexConsumer).
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inUv;
layout(location = 3) in uvec2 inLight;
layout(location = 4) in vec4 inOverlay;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vUv;
layout(location = 2) out vec2 vLight;
layout(location = 3) out vec4 vOverlay;
layout(location = 4) out vec3 vEye;

void main() {
    gl_Position = toVulkanClip(pu.projection * vec4(inPos, 1.0));
    vColor = inColor;
    vUv = inUv;
    vLight = vec2(inLight);
    vOverlay = inOverlay;
    vEye = inPos;
}
