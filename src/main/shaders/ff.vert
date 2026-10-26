#version 450

// Fixed-function vertex stage of the Vulkan GL 1.5 emulation (module C).
// Contract: ../docs/ARCHITECTURE.md (outside the repo) section 5.3; pipeline/ShaderInterface.java mirrors every offset and bit below.
// src/shadertool compiles this file twice: as is (ff.vert.spv) and with FLAT defined (ff_flat.vert.spv, flat shading).

layout(location = 0) in vec4 inPos;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec4 inTex0;
layout(location = 3) in vec4 inTex1;
layout(location = 4) in vec4 inNormal;

layout(push_constant) uniform PushConstants {
    mat4 modelView;
    vec4 color;
    vec4 normal;
    vec4 texCoord0;
    vec2 texCoord1;
    uint attribMask;
    uint reserved;
} pc;

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

// Flat shading: the colour of the provoking vertex is not interpolated. Vulkan's provoking vertex is the first one;
// the draw code (D2) orders primitives so that GL's provoking vertex comes first.
#ifdef FLAT
layout(location = 0) flat out vec4 vColor;
#else
layout(location = 0) out vec4 vColor;
#endif
layout(location = 1) out vec4 vTex0;
layout(location = 2) out vec4 vTex1;
layout(location = 3) out vec3 vEyePos;      // eye-space position, the fragment stage computes the fog from it

const uint ATTR_COLOR_BIT = 1u << 1;
const uint ATTR_TEX0_BIT = 1u << 2;
const uint ATTR_TEX1_BIT = 1u << 3;
const uint ATTR_NORMAL_BIT = 1u << 4;
const uint ATTR_TEX1_SHORT_BIT = 1u << 5;
const uint ATTR_TEX0_SHORT_BIT = 1u << 6;

const int FLAG_LIGHTING = 1 << 2;
const int FLAG_LIGHT0 = 1 << 3;
const int FLAG_LIGHT1 = 1 << 4;
const int FLAG_COLOR_MATERIAL = 1 << 5;
const int FLAG_NORMALIZE = 1 << 6;
const int FLAG_RESCALE_NORMAL = 1 << 7;
const int FLAG_TEXGEN_S = 1 << 9;
const int FLAG_TEXGEN_T = 1 << 10;
const int FLAG_TEXGEN_R = 1 << 11;
const int FLAG_TEXGEN_Q = 1 << 12;

const int TEXGEN_MODE_MASK = 3;
const int TEXGEN_MODE_SHIFT_S = 0;
const int TEXGEN_MODE_SHIFT_T = 2;
const int TEXGEN_MODE_SHIFT_R = 4;
const int TEXGEN_MODE_SHIFT_Q = 6;
const int TEXGEN_EYE_LINEAR = 1;
const int TEXGEN_SPHERE_MAP = 2;

const int COLOR_MATERIAL_AMBIENT_AND_DIFFUSE = 0;
const int COLOR_MATERIAL_AMBIENT = 1;
const int COLOR_MATERIAL_DIFFUSE = 2;
const int COLOR_MATERIAL_EMISSION = 3;

// Current normal (attribute or push constant) transformed to eye space: (M^-1)^T n, normalized only with NORMALIZE or
// RESCALE_NORMAL (without them GL uses the transformed normal as is).
vec3 eyeSpaceNormal() {
    vec3 n = (pc.attribMask & ATTR_NORMAL_BIT) != 0u ? inNormal.xyz : pc.normal.xyz;
    n = transpose(inverse(mat3(pc.modelView))) * n;
    if ((ubo.flags.x & (FLAG_NORMALIZE | FLAG_RESCALE_NORMAL)) != 0) {
        n = normalize(n);
    }
    return n;
}

// ambient + diffuse term of one light (no attenuation, no spot, no specular). VP is the unit vector from the vertex to the
// light: for a directional light (w == 0) that is the eye-space position itself, not its negation.
vec3 lightTerm(vec4 lightPos, vec3 lightAmbient, vec3 lightDiffuse, vec3 n, vec3 eye, vec3 matAmbient, vec3 matDiffuse) {
    vec3 toLight = lightPos.w == 0.0 ? lightPos.xyz : lightPos.xyz / lightPos.w - eye;
    float nDotL = max(dot(n, normalize(toLight)), 0.0);
    return matAmbient * lightAmbient + nDotL * matDiffuse * lightDiffuse;
}

// texgen mode of coordinate 0..3 (S, T, R, Q) and its generated value; OBJECT_LINEAR uses the object-space vertex,
// EYE_LINEAR the eye-space vertex (the planes were multiplied by the inverse modelview when they were set, see ShaderInterface)
float texGenLinear(int mode, vec4 plane, vec4 eyePos) {
    return mode == TEXGEN_EYE_LINEAR ? dot(eyePos, plane) : dot(inPos, plane);
}

// GL 1.5 2.11.4 SPHERE_MAP: r = reflect(u, n), m = 2 sqrt(rx^2 + ry^2 + (rz + 1)^2), (s, t) = (rx, ry) / m + 1/2
vec2 sphereMap(vec3 eye, vec3 n) {
    vec3 r = reflect(normalize(eye), normalize(n));
    float m = 2.0 * length(vec3(r.xy, r.z + 1.0));
    return m > 0.0 ? r.xy / m + 0.5 : vec2(0.5);
}

void main() {
    int flags = ubo.flags.x;

    vec4 eyePos = pc.modelView * inPos;
    gl_Position = ubo.projection * eyePos;
    // GL clip space z is [-w, w], Vulkan's is [0, w]. The Y flip is done by the (negative height) viewport.
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
    gl_PointSize = 1.0;
    vEyePos = eyePos.xyz;

    vec4 color = (pc.attribMask & ATTR_COLOR_BIT) != 0u ? inColor : pc.color;

    // ---- lighting ----
    if ((flags & FLAG_LIGHTING) != 0) {
        vec3 n = eyeSpaceNormal();
        vec3 matAmbient = ubo.materialAmbient.rgb;
        vec3 matDiffuse = ubo.materialDiffuse.rgb;
        vec3 matEmission = ubo.materialEmission.rgb;
        float diffuseAlpha = ubo.materialDiffuse.a;

        if ((flags & FLAG_COLOR_MATERIAL) != 0) {
            int mode = ubo.flags.z;
            if (mode == COLOR_MATERIAL_AMBIENT_AND_DIFFUSE) {
                matAmbient = color.rgb;
                matDiffuse = color.rgb;
                diffuseAlpha = color.a;
            } else if (mode == COLOR_MATERIAL_AMBIENT) {
                matAmbient = color.rgb;
            } else if (mode == COLOR_MATERIAL_DIFFUSE) {
                matDiffuse = color.rgb;
                diffuseAlpha = color.a;
            } else if (mode == COLOR_MATERIAL_EMISSION) {
                matEmission = color.rgb;
            }   // COLOR_MATERIAL_SPECULAR: there is no specular term
        }

        vec3 lit = matEmission + matAmbient * ubo.lightModelAmbient.rgb;
        if ((flags & FLAG_LIGHT0) != 0) {
            lit += lightTerm(ubo.light0Position, ubo.light0Ambient.rgb, ubo.light0Diffuse.rgb, n, eyePos.xyz, matAmbient, matDiffuse);
        }
        if ((flags & FLAG_LIGHT1) != 0) {
            lit += lightTerm(ubo.light1Position, ubo.light1Ambient.rgb, ubo.light1Diffuse.rgb, n, eyePos.xyz, matAmbient, matDiffuse);
        }
        color = vec4(lit, diffuseAlpha);
    }
    vColor = clamp(color, 0.0, 1.0);    // GL clamps the primary color, lit or not

    // ---- texture coordinates, unit 0 ----
    vec4 tc0 = pc.texCoord0;
    if ((pc.attribMask & ATTR_TEX0_BIT) != 0u) {
        tc0 = inTex0;
        if ((pc.attribMask & ATTR_TEX0_SHORT_BIT) != 0u) {
            tc0.xy *= 32767.0;      // GL_SHORT coordinates arrive as R16G16_SNORM: undo the normalization
        }
    }

    int texGenModes = ubo.flags.y;
    int modeS = (texGenModes >> TEXGEN_MODE_SHIFT_S) & TEXGEN_MODE_MASK;
    int modeT = (texGenModes >> TEXGEN_MODE_SHIFT_T) & TEXGEN_MODE_MASK;
    bool sphereS = (flags & FLAG_TEXGEN_S) != 0 && modeS == TEXGEN_SPHERE_MAP;
    bool sphereT = (flags & FLAG_TEXGEN_T) != 0 && modeT == TEXGEN_SPHERE_MAP;
    if (sphereS || sphereT) {
        // the sphere map needs the eye-space normal whether or not lighting is enabled
        vec2 st = sphereMap(eyePos.xyz, eyeSpaceNormal());
        if (sphereS) {
            tc0.s = st.x;
        }
        if (sphereT) {
            tc0.t = st.y;
        }
    }
    if ((flags & FLAG_TEXGEN_S) != 0 && modeS != TEXGEN_SPHERE_MAP) {
        tc0.s = texGenLinear(modeS, ubo.texGenPlaneS, eyePos);
    }
    if ((flags & FLAG_TEXGEN_T) != 0 && modeT != TEXGEN_SPHERE_MAP) {
        tc0.t = texGenLinear(modeT, ubo.texGenPlaneT, eyePos);
    }
    if ((flags & FLAG_TEXGEN_R) != 0) {
        tc0.p = texGenLinear((texGenModes >> TEXGEN_MODE_SHIFT_R) & TEXGEN_MODE_MASK, ubo.texGenPlaneR, eyePos);
    }
    if ((flags & FLAG_TEXGEN_Q) != 0) {
        tc0.q = texGenLinear((texGenModes >> TEXGEN_MODE_SHIFT_Q) & TEXGEN_MODE_MASK, ubo.texGenPlaneQ, eyePos);
    }
    vTex0 = ubo.texMatrix0 * tc0;

    // ---- texture coordinates, unit 1 (lightmap) ----
    vec4 tc1 = vec4(pc.texCoord1, 0.0, 1.0);
    if ((pc.attribMask & ATTR_TEX1_BIT) != 0u) {
        tc1 = inTex1;
        if ((pc.attribMask & ATTR_TEX1_SHORT_BIT) != 0u) {
            tc1.xy *= 32767.0;      // lightmap shorts, see above
        }
    }
    vTex1 = ubo.texMatrix1 * tc1;
}
