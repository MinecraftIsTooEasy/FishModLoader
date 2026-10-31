#version 450
#extension GL_GOOGLE_include_directive : require
#include "../include/fragment.glsl"

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUv;
layout(location = 2) in vec2 vLight;
layout(location = 3) in vec4 vOverlay;
layout(location = 4) in vec3 vEye;

layout(location = 0) out vec4 outColor;

void main() {
    outColor = shadeFragment(vColor, vUv, vLight, vOverlay, vEye);
}
