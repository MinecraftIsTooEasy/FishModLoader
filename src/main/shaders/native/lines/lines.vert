#version 450
#extension GL_GOOGLE_include_directive : require
#include "../include/common.glsl"

// LINE vertex format: every segment is four vertices (two per endpoint, side -1 / +1) expanded into a screen space quad of
// pc.params.y pixels (default 2) so the width is the same on every platform.
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec3 inOther;
layout(location = 3) in int inSide;

layout(location = 0) out vec4 vColor;
layout(location = 4) out vec3 vEye;

const float NEAR_W = 1.0e-4;

void main() {
    vec4 a = pu.projection * vec4(inPos, 1.0);
    vec4 b = pu.projection * vec4(inOther, 1.0);
    // clip the segment against w = NEAR_W so that endpoints behind the camera do not flip the direction
    if (a.w < NEAR_W && b.w >= NEAR_W) {
        a = mix(a, b, (NEAR_W - a.w) / (b.w - a.w));
    }
    if (b.w < NEAR_W && a.w >= NEAR_W) {
        b = mix(b, a, (NEAR_W - b.w) / (a.w - b.w));
    }
    vec2 half_px = pu.viewport.xy * 0.5;
    vec2 pa = a.xy / max(a.w, NEAR_W) * half_px;
    vec2 pb = b.xy / max(b.w, NEAR_W) * half_px;
    vec2 dir = pb - pa;
    float len = length(dir);
    dir = len > 1.0e-6 ? dir / len : vec2(1.0, 0.0);
    float width = pc.params.y > 0.0 ? pc.params.y : 2.0;
    vec2 offsetPx = vec2(-dir.y, dir.x) * (float(inSide) * 0.5 * width);
    vec4 clip = a;
    clip.xy += offsetPx / half_px * a.w;
    gl_Position = toVulkanClip(clip);
    vColor = inColor;
    vEye = inPos;
}
