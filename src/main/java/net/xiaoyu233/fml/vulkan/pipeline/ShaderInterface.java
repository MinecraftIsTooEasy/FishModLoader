package net.xiaoyu233.fml.vulkan.pipeline;

/**
 * Byte-exact contract between the fixed-function GLSL (module C) and the GL state packing / draw code (modules D1, D2).
 * Mirrors ../docs/ARCHITECTURE.md (outside the repo) section 5.3; all offsets are in bytes, matrices are column-major (GL order),
 * the uniform block is std140.
 */
public final class ShaderInterface {
    private ShaderInterface() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Vertex inputs: all five are always declared and always provided; absent arrays are fed from the dummy binding
    // (input rate INSTANCE, stride 16, a 16-byte zero buffer).
    // ---------------------------------------------------------------------------------------------------------

    /** inPos, vec4 (w defaults to 1). */
    public static final int LOCATION_POSITION = 0;
    /** inColor, vec4. */
    public static final int LOCATION_COLOR = 1;
    /** inTex0, vec4 (R16G16_SNORM is scaled by 32767 when ATTR_TEX0_SHORT_BIT is set). */
    public static final int LOCATION_TEX0 = 2;
    /** inTex1, vec4 (R16G16_SNORM is scaled by 32767 when ATTR_TEX1_SHORT_BIT is set). */
    public static final int LOCATION_TEX1 = 3;
    /** inNormal, vec4 (xyz used). */
    public static final int LOCATION_NORMAL = 4;

    public static final int DUMMY_BINDING = 7;
    public static final int DUMMY_BINDING_STRIDE = 16;
    public static final int DUMMY_BUFFER_SIZE = 16;

    // ---------------------------------------------------------------------------------------------------------
    // Push constants: 128 bytes, VERTEX | FRAGMENT stages
    // ---------------------------------------------------------------------------------------------------------

    public static final int PUSH_CONSTANT_SIZE = 128;

    /** mat4 modelView. */
    public static final int PC_MODEL_VIEW = 0;
    /** vec4 current color (used when ATTR_COLOR_BIT is not set in attribMask). */
    public static final int PC_COLOR = 64;
    /** vec4 current normal xyz (used when ATTR_NORMAL_BIT is not set). */
    public static final int PC_NORMAL = 80;
    /** vec4 current texcoord unit 0 (s,t,r,q) (used when ATTR_TEX0_BIT is not set and there is no texgen). */
    public static final int PC_TEXCOORD0 = 96;
    /** vec2 current texcoord unit 1 (s,t) (used when ATTR_TEX1_BIT is not set). */
    public static final int PC_TEXCOORD1 = 112;
    /** uint attribMask, see ATTR_*_BIT. */
    public static final int PC_ATTRIB_MASK = 120;
    /** uint reserved. */
    public static final int PC_RESERVED = 124;

    // attribMask bits (bit 0 is unused: the position is always present)
    public static final int ATTR_COLOR_BIT = 1 << 1;
    public static final int ATTR_TEX0_BIT = 1 << 2;
    public static final int ATTR_TEX1_BIT = 1 << 3;
    public static final int ATTR_NORMAL_BIT = 1 << 4;
    public static final int ATTR_TEX1_SHORT_BIT = 1 << 5;
    public static final int ATTR_TEX0_SHORT_BIT = 1 << 6;

    // ---------------------------------------------------------------------------------------------------------
    // Descriptor sets: set 0 = uniform buffer (UNIFORM_BUFFER_DYNAMIC, std140), set 1 = texture unit 0, set 2 = texture
    // unit 1 (lightmap); sets 1 and 2 use the same VkDescriptorSetLayout (combined image sampler at binding 0).
    // ---------------------------------------------------------------------------------------------------------

    public static final int SET_UNIFORMS = 0;
    public static final int BINDING_UNIFORMS = 0;
    public static final int SET_TEXTURE0 = 1;
    public static final int SET_TEXTURE1 = 2;
    public static final int BINDING_TEXTURE = 0;

    /** Specialization constant id of the alpha test switch (bool; pipelines without alpha test contain no discard). */
    public static final int SPEC_ALPHA_TEST = 0;

    // ---------------------------------------------------------------------------------------------------------
    // Uniform buffer (set 0, binding 0), 512 bytes
    // ---------------------------------------------------------------------------------------------------------

    public static final int UBO_SIZE = 512;

    /** mat4 projection. */
    public static final int UBO_PROJECTION = 0;
    /** mat4 texMatrix0. */
    public static final int UBO_TEX_MATRIX0 = 64;
    /** mat4 texMatrix1. */
    public static final int UBO_TEX_MATRIX1 = 128;
    /** vec4 fogColor. */
    public static final int UBO_FOG_COLOR = 192;
    /** vec4 fogParams: x = start, y = end, z = density, w = 1 / (end - start) (0 if end == start). */
    public static final int UBO_FOG_PARAMS = 208;
    /** vec4 lightModelAmbient. */
    public static final int UBO_LIGHT_MODEL_AMBIENT = 224;
    /** vec4 light0Position, eye space (transformed by the modelview current at glLight time); w = 0 is directional. */
    public static final int UBO_LIGHT0_POSITION = 240;
    public static final int UBO_LIGHT0_DIFFUSE = 256;
    public static final int UBO_LIGHT0_AMBIENT = 272;
    public static final int UBO_LIGHT1_POSITION = 288;
    public static final int UBO_LIGHT1_DIFFUSE = 304;
    public static final int UBO_LIGHT1_AMBIENT = 320;
    public static final int UBO_MATERIAL_AMBIENT = 336;
    public static final int UBO_MATERIAL_DIFFUSE = 352;
    public static final int UBO_MATERIAL_EMISSION = 368;
    /** vec4 texEnvColor0. */
    public static final int UBO_TEX_ENV_COLOR0 = 384;
    /** vec4 texGenPlaneS/T/R/Q of unit 0; EYE_LINEAR planes are pre-multiplied by inverse(modelview at glTexGen time). */
    public static final int UBO_TEXGEN_PLANE_S = 400;
    public static final int UBO_TEXGEN_PLANE_T = 416;
    public static final int UBO_TEXGEN_PLANE_R = 432;
    public static final int UBO_TEXGEN_PLANE_Q = 448;

    /** ivec4 modes (start of the block); the components are listed below. */
    public static final int UBO_MODES = 464;
    /** modes.x: fog mode, FOG_*. */
    public static final int UBO_MODES_FOG_MODE = UBO_MODES;
    /** modes.y: alpha func = GL func - 0x200 (ALPHA_FUNC_*). */
    public static final int UBO_MODES_ALPHA_FUNC = UBO_MODES + 4;
    /** modes.z: tex env mode of unit 0 (TEXENV_*). */
    public static final int UBO_MODES_TEX_ENV0 = UBO_MODES + 8;
    /** modes.w: tex env mode of unit 1 (TEXENV_*). */
    public static final int UBO_MODES_TEX_ENV1 = UBO_MODES + 12;

    /** ivec4 flags (start of the block); the components are listed below. */
    public static final int UBO_FLAGS = 480;
    /** flags.x: FLAG_* bits. */
    public static final int UBO_FLAGS_BITS = UBO_FLAGS;
    /** flags.y: texgen modes, 2 bits each for S, T, R, Q (TEXGEN_MODE_SHIFT_*, TEXGEN_*). */
    public static final int UBO_FLAGS_TEXGEN_MODES = UBO_FLAGS + 4;
    /** flags.z: color material mode (COLOR_MATERIAL_*). */
    public static final int UBO_FLAGS_COLOR_MATERIAL_MODE = UBO_FLAGS + 8;
    /** flags.w: unused. */
    public static final int UBO_FLAGS_W = UBO_FLAGS + 12;

    /** vec4 alphaRef: x = alpha test reference. */
    public static final int UBO_ALPHA_REF = 496;

    // fog modes (modes.x)
    public static final int FOG_OFF = 0;
    public static final int FOG_LINEAR = 1;
    public static final int FOG_EXP = 2;
    public static final int FOG_EXP2 = 3;

    // alpha functions (modes.y) = GL_NEVER.. GL_ALWAYS - 0x200
    public static final int ALPHA_FUNC_NEVER = 0;
    public static final int ALPHA_FUNC_LESS = 1;
    public static final int ALPHA_FUNC_EQUAL = 2;
    public static final int ALPHA_FUNC_LEQUAL = 3;
    public static final int ALPHA_FUNC_GREATER = 4;
    public static final int ALPHA_FUNC_NOTEQUAL = 5;
    public static final int ALPHA_FUNC_GEQUAL = 6;
    public static final int ALPHA_FUNC_ALWAYS = 7;

    // tex env modes (modes.z, modes.w); GL_COMBINE is evaluated as MODULATE
    public static final int TEXENV_MODULATE = 0;
    public static final int TEXENV_REPLACE = 1;
    public static final int TEXENV_DECAL = 2;
    public static final int TEXENV_BLEND = 3;
    public static final int TEXENV_ADD = 4;
    public static final int TEXENV_COMBINE = 5;

    // flags.x bits
    public static final int FLAG_TEX0_ENABLED = 1 << 0;
    public static final int FLAG_TEX1_ENABLED = 1 << 1;
    public static final int FLAG_LIGHTING = 1 << 2;
    public static final int FLAG_LIGHT0 = 1 << 3;
    public static final int FLAG_LIGHT1 = 1 << 4;
    public static final int FLAG_COLOR_MATERIAL = 1 << 5;
    public static final int FLAG_NORMALIZE = 1 << 6;
    public static final int FLAG_RESCALE_NORMAL = 1 << 7;
    public static final int FLAG_FOG_RADIAL = 1 << 8;
    public static final int FLAG_TEXGEN_S = 1 << 9;
    public static final int FLAG_TEXGEN_T = 1 << 10;
    public static final int FLAG_TEXGEN_R = 1 << 11;
    public static final int FLAG_TEXGEN_Q = 1 << 12;

    // flags.y: texgen modes, 2 bits per coordinate
    public static final int TEXGEN_MODE_SHIFT_S = 0;
    public static final int TEXGEN_MODE_SHIFT_T = 2;
    public static final int TEXGEN_MODE_SHIFT_R = 4;
    public static final int TEXGEN_MODE_SHIFT_Q = 6;
    public static final int TEXGEN_MODE_MASK = 0x3;
    public static final int TEXGEN_OBJECT_LINEAR = 0;
    public static final int TEXGEN_EYE_LINEAR = 1;
    public static final int TEXGEN_SPHERE_MAP = 2;

    // flags.z: color material mode
    public static final int COLOR_MATERIAL_AMBIENT_AND_DIFFUSE = 0;
    public static final int COLOR_MATERIAL_AMBIENT = 1;
    public static final int COLOR_MATERIAL_DIFFUSE = 2;
    public static final int COLOR_MATERIAL_EMISSION = 3;
    public static final int COLOR_MATERIAL_SPECULAR = 4;
}
