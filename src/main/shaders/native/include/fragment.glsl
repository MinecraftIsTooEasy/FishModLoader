// Fragment pipeline of GL 1.5 in the exact order MITE relied on (ARCHITECTURE-NATIVE 1.9):
//   c = colour * texture(unit 0)  ->  * lightmap  ->  overlay mix  ->  alpha test  ->  fog
#ifndef FML_NATIVE_FRAGMENT
#define FML_NATIVE_FRAGMENT

#include "common.glsl"

layout(set = 1, binding = 0) uniform sampler2D uTexture;
layout(set = 2, binding = 0) uniform sampler2D uLightmap;

// GL fog factor of the radial eye distance (MITE enables GL_EYE_RADIAL_NV); 1 = no fog.
float fogFactor(vec3 eye) {
    int mode = int(pu.fogParams.w + 0.5);
    float d = length(eye);
    float f = 1.0;
    if (mode == 1) {
        f = (pu.fogParams.y - d) / (pu.fogParams.y - pu.fogParams.x);
    } else if (mode == 2) {
        f = exp(-pu.fogParams.z * d);
    } else if (mode == 3) {
        float x = pu.fogParams.z * d;
        f = exp(-x * x);
    }
    return clamp(f, 0.0, 1.0);
}

// vColor: lit vertex colour, uv: unit 0 coordinates, light: lightmap coordinate in 1/256 units without the +8 centre offset,
// overlay: rgb + mix factor, eye: eye-space position for the fog distance.
vec4 shadeFragment(vec4 vColor, vec2 uv, vec2 light, vec4 overlay, vec3 eye) {
    vec4 c = vColor;
    if (TEXTURED) {
        c *= texture(uTexture, uv);
    }
    if (LIGHTMAP) {
        c *= texture(uLightmap, (light + 8.0) / 256.0);
    }
    if (OVERLAY) {
        c.rgb = mix(c.rgb, overlay.rgb, overlay.a);
    }
    int mode = ALPHA_MODE != 0 ? ALPHA_MODE : (CUTOUT_LAYER ? 1 : 0);
    if (mode == 1) {
        if (!(c.a > ALPHA_REF)) {
            discard;
        }
    } else if (mode == 2) {
        if (!(c.a > pc.params.x)) {
            discard;
        }
    }
    if (DEPTH_ONLY) {
        return vec4(0.0);
    }
    if (FOG) {
        c.rgb = mix(pu.fogColor.rgb, c.rgb, fogFactor(eye));
    }
    return c;
}

#endif
