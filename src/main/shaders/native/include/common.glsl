// Shared declarations of the native programs (ARCHITECTURE-NATIVE 1.9): specialisation constants, pass uniform block, push constants.
// Included by every native vertex and fragment stage; the layout mirrors render/gpu/PassUniforms.java and render/gpu/PipelineLibrary.java.
#ifndef FML_NATIVE_COMMON
#define FML_NATIVE_COMMON

// Specialisation constants (constant_id = bit position of the material key family, see PipelineLibrary).
layout(constant_id = 0) const int ALPHA_MODE = 0;          // 0 none, 1 fixed GREATER ALPHA_REF, 2 dynamic reference in pc.params.x
layout(constant_id = 1) const float ALPHA_REF = 0.1;
layout(constant_id = 2) const bool TEXTURED = true;        // unit 0 texture modulates the colour
layout(constant_id = 3) const bool LIGHTMAP = false;       // unit 1 lightmap modulates the colour (blit.frag: anaglyph composite)
layout(constant_id = 4) const bool FOG = false;
layout(constant_id = 5) const bool OVERLAY = false;        // per-vertex overlay colour mixed in before the alpha test
layout(constant_id = 6) const bool CUTOUT_LAYER = false;   // terrain: the layer is alpha tested (forces ALPHA_MODE 1 when it is 0)
layout(constant_id = 7) const bool DEPTH_ONLY = false;     // colour writes are masked: only the alpha test and depth matter

// PassUniforms, std140, 256 bytes, dynamic offset.
layout(std140, set = 0, binding = 0) uniform PassUniforms {
    mat4 projection;       //   0  GL projection (column major, GL clip space)
    vec4 fogColor;         //  64
    vec4 fogParams;        //  80  x start, y end, z density, w mode (1 LINEAR, 2 EXP, 3 EXP2)
    vec4 viewport;         //  96  w, h, 1/w, 1/h in pixels of the bound target
    vec4 misc;             // 112  x time in seconds
    mat4 terrainView;      // 128  camera modelview (rotation, bobbing) at TerrainRenderer.draw* time
    vec4 terrainParams;    // 192  x section scale, y section bias (world = p * x - y)
    vec4 reserved0;        // 208
    vec4 reserved1;
    vec4 reserved2;
} pu;

// Push constants, 128 bytes, vertex + fragment.
layout(push_constant) uniform PushConstants {
    mat4 model;            //   0  object -> eye (STATIC); identity otherwise
    vec4 colorMod;         //  64  colour modulator (STATIC)
    vec4 params;           //  80  x dynamic alpha reference, y line width in pixels, z portal layer, w time in seconds
    vec4 extra[2];         //  96  program specific
} pc;

// GL clip space z in [-w, w] -> Vulkan [0, w]; the Y flip is done by the negative height viewport (P1 5.3).
vec4 toVulkanClip(vec4 clip) {
    clip.z = (clip.z + clip.w) * 0.5;
    return clip;
}

#endif
