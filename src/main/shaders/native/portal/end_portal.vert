#version 450
// PORTAL program (ARCHITECTURE-NATIVE 1.9): one draw per RenderEndPortal layer.
//
// PUSH CONSTANT LAYOUT (128 bytes, vertex + fragment stage flags) -- contract with subtask A-portal (PortalRenderer).
// This program does NOT use the shared PushConstants block of include/common.glsl; it declares its own (the whole 128 B):
//   offset   0  mat4 texgen   column major. Rows 0..2 are the GL_OBJECT_LINEAR planes of S, T, R; row 3 is the Q plane, which the
//                             CPU has already folded with the pose at glTexGen time (Q = y - ty). All four rows are applied to the
//                             object position with w = 1, so in GL terms gen = (dot(row0, obj), dot(row1, obj), dot(row2, obj),
//                             dot(row3, obj)). Java: write the matrix with m[col*4+row] = plane[row][col] (plane i = row i).
//   offset  64  mat4 uv       the GL texture matrix of the layer (translate/scale/rotate ops of MITE), column major;
//                             final texcoord = uv * gen.
// The layer colour (MITE glColor per layer) is the vertex colour (location 2, RGBA8 unorm). Time is not needed in the
// shader: MITE's time dependent translation is folded into `uv` on the CPU (PassUniforms.misc.x is still available).
// Spec constant FOG enables fog (fog parameters in the set 0 PassUniforms, radial eye distance). Texture: set 1 binding 0.
#extension GL_GOOGLE_include_directive : require

// Same set 0 block as include/common.glsl (not included here: its push constant block would clash with the one below).
layout(std140, set = 0, binding = 0) uniform PassUniforms {
    mat4 projection;
    vec4 fogColor;
    vec4 fogParams;
    vec4 viewport;
    vec4 misc;
    mat4 terrainView;
    vec4 terrainParams;
    vec4 reserved0;
    vec4 reserved1;
    vec4 reserved2;
} pu;

layout(push_constant) uniform PortalPush {
    mat4 texgen;           //   0
    mat4 uv;               //  64
} pp;

layout(location = 0) in vec3 inPos;      // eye space
layout(location = 1) in vec3 inObj;      // object space (texgen input)
layout(location = 2) in vec4 inColor;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec4 vTex;     // projective (s, t, r, q), perspective-correct interpolated
layout(location = 2) out vec3 vEye;

void main() {
    vec4 clip = pu.projection * vec4(inPos, 1.0);
    clip.z = (clip.z + clip.w) * 0.5;    // GL clip z -> Vulkan (toVulkanClip)
    gl_Position = clip;
    vec4 obj = vec4(inObj, 1.0);
    vec4 eye = vec4(inPos, 1.0);
    vec4 gen = vec4(dot(vec4(pp.texgen[0][0], pp.texgen[1][0], pp.texgen[2][0], pp.texgen[3][0]), obj),
                    dot(vec4(pp.texgen[0][1], pp.texgen[1][1], pp.texgen[2][1], pp.texgen[3][1]), obj),
                    dot(vec4(pp.texgen[0][2], pp.texgen[1][2], pp.texgen[2][2], pp.texgen[3][2]), obj),
                    dot(vec4(pp.texgen[0][3], pp.texgen[1][3], pp.texgen[2][3], pp.texgen[3][3]), obj));
    vTex = pp.uv * gen;
    vColor = inColor;
    vEye = inPos;
}
