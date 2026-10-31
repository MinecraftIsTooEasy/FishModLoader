#version 450
#extension GL_GOOGLE_include_directive : require
#include "../include/common.glsl"

// Static meshes (sky disc, stars, cloud lists): UNIVERSAL vertices in object space; pc.model = pose at draw time,
// pc.colorMod = the colour current when the GL display list was drawn.
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
    vec4 eye = pc.model * vec4(inPos, 1.0);
    gl_Position = toVulkanClip(pu.projection * eye);
    vColor = inColor * pc.colorMod;
    vUv = inUv;
    vLight = vec2(inLight);
    vOverlay = inOverlay;
    vEye = eye.xyz;
}
