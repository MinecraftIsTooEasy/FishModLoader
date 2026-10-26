#version 450

// Fixed-function fragment stage of the Vulkan GL 1.5 emulation (module C): texture environment of units 0 and 1, fog, alpha test.
// Contract: ../docs/ARCHITECTURE.md (outside the repo) section 5.3; pipeline/ShaderInterface.java mirrors every offset and bit below.
// src/shadertool compiles this file twice: as is (ff.frag.spv) and with FLAT defined (ff_flat.frag.spv, flat shading).

// Must match ff.vert: with FLAT the color of the provoking vertex is used as is, interpolation qualifiers have to agree.
#ifdef FLAT
layout(location = 0) flat in vec4 vColor;
#else
layout(location = 0) in vec4 vColor;
#endif
layout(location = 1) in vec4 vTex0;
layout(location = 2) in vec4 vTex1;
layout(location = 3) in vec3 vEyePos;

layout(location = 0) out vec4 outColor;

layout(std140, set = 0, binding = 0) uniform UniformBuffer {
    mat4 projection;
    mat4 texMatrix0;
    mat4 texMatrix1;
    vec4 fogColor;
    vec4 fogParams;
    vec4 lightModelAmbient;
    vec4 light0Position;
    vec4 light0Diffuse;
    vec4 light0Ambient;
    vec4 light1Position;
    vec4 light1Diffuse;
    vec4 light1Ambient;
    vec4 materialAmbient;
    vec4 materialDiffuse;
    vec4 materialEmission;
    vec4 texEnvColor0;
    vec4 texGenPlaneS;
    vec4 texGenPlaneT;
    vec4 texGenPlaneR;
    vec4 texGenPlaneQ;
    ivec4 modes;
    ivec4 flags;
    vec4 alphaRef;
} ubo;

layout(set = 1, binding = 0) uniform sampler2D texUnit0;
layout(set = 2, binding = 0) uniform sampler2D texUnit1;

// Specialization constant: pipelines without alpha test contain no discard (matters for the early depth test on tile based GPUs).
layout(constant_id = 0) const bool ALPHA_TEST = false;

const int FLAG_TEX0_ENABLED = 1 << 0;
const int FLAG_TEX1_ENABLED = 1 << 1;
const int FLAG_FOG_RADIAL = 1 << 8;

const int FOG_OFF = 0;
const int FOG_LINEAR = 1;
const int FOG_EXP = 2;
const int FOG_EXP2 = 3;

const int TEXENV_REPLACE = 1;
const int TEXENV_DECAL = 2;
const int TEXENV_BLEND = 3;
const int TEXENV_ADD = 4;

const int ALPHA_FUNC_NEVER = 0;
const int ALPHA_FUNC_LESS = 1;
const int ALPHA_FUNC_EQUAL = 2;
const int ALPHA_FUNC_LEQUAL = 3;
const int ALPHA_FUNC_GREATER = 4;
const int ALPHA_FUNC_NOTEQUAL = 5;
const int ALPHA_FUNC_GEQUAL = 6;

// GL 1.5 table 3.20 for an RGBA texture. cf = incoming fragment color (the previous unit's result), texel = texture color.
// MODULATE is the default; COMBINE (5) is evaluated as MODULATE as well.
vec4 applyTexEnv(int mode, vec4 texel, vec4 cf) {
    if (mode == TEXENV_REPLACE) {
        return texel;
    } else if (mode == TEXENV_DECAL) {
        return vec4(mix(cf.rgb, texel.rgb, texel.a), cf.a);
    } else if (mode == TEXENV_BLEND) {
        return vec4(mix(cf.rgb, ubo.texEnvColor0.rgb, texel.rgb), cf.a * texel.a);
    } else if (mode == TEXENV_ADD) {
        return vec4(min(cf.rgb + texel.rgb, 1.0), cf.a * texel.a);
    }
    return cf * texel;
}

bool alphaPasses(float alpha, int func) {
    float ref = ubo.alphaRef.x;
    if (func == ALPHA_FUNC_NEVER) return false;
    if (func == ALPHA_FUNC_LESS) return alpha < ref;
    if (func == ALPHA_FUNC_EQUAL) return alpha == ref;
    if (func == ALPHA_FUNC_LEQUAL) return alpha <= ref;
    if (func == ALPHA_FUNC_GREATER) return alpha > ref;
    if (func == ALPHA_FUNC_NOTEQUAL) return alpha != ref;
    if (func == ALPHA_FUNC_GEQUAL) return alpha >= ref;
    return true;    // ALPHA_FUNC_ALWAYS
}

void main() {
    int flags = ubo.flags.x;
    vec4 color = vColor;

    if ((flags & FLAG_TEX0_ENABLED) != 0) {
        color = applyTexEnv(ubo.modes.z, texture(texUnit0, vTex0.xy / vTex0.w), color);
    }
    if ((flags & FLAG_TEX1_ENABLED) != 0) {
        color = applyTexEnv(ubo.modes.w, texture(texUnit1, vTex1.xy / vTex1.w), color);
    }

    // fog (rgb only): c = |ze| or, with NV_fog_distance, the radial eye distance; f is clamped to [0, 1]
    int fogMode = ubo.modes.x;
    if (fogMode != FOG_OFF) {
        float c = (flags & FLAG_FOG_RADIAL) != 0 ? length(vEyePos) : abs(vEyePos.z);
        float f;
        if (fogMode == FOG_LINEAR) {
            f = (ubo.fogParams.y - c) * ubo.fogParams.w;        // (end - c) / (end - start)
        } else if (fogMode == FOG_EXP) {
            f = exp(-ubo.fogParams.z * c);
        } else {
            float d = ubo.fogParams.z * c;                       // FOG_EXP2
            f = exp(-d * d);
        }
        color.rgb = mix(ubo.fogColor.rgb, color.rgb, clamp(f, 0.0, 1.0));
    }

    if (ALPHA_TEST && !alphaPasses(color.a, ubo.modes.y)) {
        discard;
    }

    outColor = color;
}
