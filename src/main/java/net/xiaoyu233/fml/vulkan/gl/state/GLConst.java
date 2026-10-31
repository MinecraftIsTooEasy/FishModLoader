package net.xiaoyu233.fml.vulkan.gl.state;

/**
 * GL enumerants used by the state machine (values of the OpenGL 1.5 registry plus the few extension enums it understands),
 * kept in one place so GLState / StateQuery / AttribStack do not depend on which LWJGL 2 facade class declares a constant.
 */
final class GLConst {
    private GLConst() {
    }

    // errors
    static final int GL_INVALID_ENUM = 0x0500;
    static final int GL_INVALID_VALUE = 0x0501;
    static final int GL_INVALID_OPERATION = 0x0502;
    static final int GL_STACK_OVERFLOW = 0x0503;
    static final int GL_STACK_UNDERFLOW = 0x0504;

    // matrix modes
    static final int GL_MODELVIEW = 0x1700;
    static final int GL_PROJECTION = 0x1701;
    static final int GL_TEXTURE = 0x1702;

    // faces, winding, polygon modes, shading
    static final int GL_NONE = 0;
    static final int GL_FRONT = 0x0404;
    static final int GL_BACK = 0x0405;
    static final int GL_FRONT_AND_BACK = 0x0408;
    static final int GL_FRONT_LEFT = 0x0400;
    static final int GL_BACK_LEFT = 0x0402;
    static final int GL_LEFT = 0x0406;
    static final int GL_CW = 0x0900;
    static final int GL_CCW = 0x0901;
    static final int GL_POINT = 0x1B00;
    static final int GL_LINE = 0x1B01;
    static final int GL_FILL = 0x1B02;
    static final int GL_FLAT = 0x1D00;
    static final int GL_SMOOTH = 0x1D01;

    // compare functions
    static final int GL_NEVER = 0x0200;
    static final int GL_LESS = 0x0201;
    static final int GL_EQUAL = 0x0202;
    static final int GL_LEQUAL = 0x0203;
    static final int GL_GREATER = 0x0204;
    static final int GL_NOTEQUAL = 0x0205;
    static final int GL_GEQUAL = 0x0206;
    static final int GL_ALWAYS = 0x0207;

    // blending
    static final int GL_ZERO = 0;
    static final int GL_ONE = 1;
    static final int GL_SRC_COLOR = 0x0300;
    static final int GL_ONE_MINUS_SRC_COLOR = 0x0301;
    static final int GL_SRC_ALPHA = 0x0302;
    static final int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
    static final int GL_DST_ALPHA = 0x0304;
    static final int GL_ONE_MINUS_DST_ALPHA = 0x0305;
    static final int GL_DST_COLOR = 0x0306;
    static final int GL_ONE_MINUS_DST_COLOR = 0x0307;
    static final int GL_SRC_ALPHA_SATURATE = 0x0308;
    static final int GL_CONSTANT_COLOR = 0x8001;
    static final int GL_ONE_MINUS_CONSTANT_COLOR = 0x8002;
    static final int GL_CONSTANT_ALPHA = 0x8003;
    static final int GL_ONE_MINUS_CONSTANT_ALPHA = 0x8004;
    static final int GL_FUNC_ADD = 0x8006;
    static final int GL_MIN = 0x8007;
    static final int GL_MAX = 0x8008;
    static final int GL_FUNC_SUBTRACT = 0x800A;
    static final int GL_FUNC_REVERSE_SUBTRACT = 0x800B;

    // logic ops
    static final int GL_CLEAR = 0x1500;
    static final int GL_AND = 0x1501;
    static final int GL_AND_REVERSE = 0x1502;
    static final int GL_COPY = 0x1503;
    static final int GL_AND_INVERTED = 0x1504;
    static final int GL_NOOP = 0x1505;
    static final int GL_XOR = 0x1506;
    static final int GL_OR = 0x1507;
    static final int GL_NOR = 0x1508;
    static final int GL_EQUIV = 0x1509;
    static final int GL_INVERT = 0x150A;
    static final int GL_OR_REVERSE = 0x150B;
    static final int GL_COPY_INVERTED = 0x150C;
    static final int GL_OR_INVERTED = 0x150D;
    static final int GL_NAND = 0x150E;
    static final int GL_SET = 0x150F;

    // stencil
    static final int GL_KEEP = 0x1E00;

    // fog
    static final int GL_EXP = 0x0800;
    static final int GL_EXP2 = 0x0801;
    static final int GL_LINEAR = 0x2601;
    static final int GL_FOG_DISTANCE_MODE_NV = 0x855A;
    static final int GL_EYE_RADIAL_NV = 0x855B;
    static final int GL_EYE_PLANE_ABSOLUTE_NV = 0x855C;

    // lighting
    static final int GL_AMBIENT = 0x1200;
    static final int GL_DIFFUSE = 0x1201;
    static final int GL_SPECULAR = 0x1202;
    static final int GL_POSITION = 0x1203;
    static final int GL_SPOT_DIRECTION = 0x1204;
    static final int GL_SPOT_EXPONENT = 0x1205;
    static final int GL_SPOT_CUTOFF = 0x1206;
    static final int GL_CONSTANT_ATTENUATION = 0x1207;
    static final int GL_LINEAR_ATTENUATION = 0x1208;
    static final int GL_QUADRATIC_ATTENUATION = 0x1209;
    static final int GL_EMISSION = 0x1600;
    static final int GL_SHININESS = 0x1601;
    static final int GL_AMBIENT_AND_DIFFUSE = 0x1602;
    static final int GL_COLOR_INDEXES = 0x1603;
    static final int GL_LIGHT_MODEL_LOCAL_VIEWER = 0x0B51;
    static final int GL_LIGHT_MODEL_TWO_SIDE = 0x0B52;
    static final int GL_LIGHT_MODEL_AMBIENT = 0x0B53;
    static final int GL_LIGHT_MODEL_COLOR_CONTROL = 0x81F8;
    static final int GL_SINGLE_COLOR = 0x81F9;
    static final int GL_SEPARATE_SPECULAR_COLOR = 0x81FA;

    // texture environment
    static final int GL_TEXTURE_ENV = 0x2300;
    static final int GL_TEXTURE_ENV_MODE = 0x2200;
    static final int GL_TEXTURE_ENV_COLOR = 0x2201;
    static final int GL_TEXTURE_FILTER_CONTROL = 0x8500;
    static final int GL_TEXTURE_LOD_BIAS = 0x8501;
    static final int GL_POINT_SPRITE = 0x8861;
    static final int GL_COORD_REPLACE = 0x8862;
    static final int GL_MODULATE = 0x2100;
    static final int GL_DECAL = 0x2101;
    static final int GL_BLEND = 0x0BE2;
    static final int GL_REPLACE = 0x1E01;
    static final int GL_ADD = 0x0104;
    static final int GL_COMBINE = 0x8570;
    static final int GL_COMBINE_RGB = 0x8571;
    static final int GL_COMBINE_ALPHA = 0x8572;
    static final int GL_RGB_SCALE = 0x8573;
    static final int GL_ALPHA_SCALE = 0x0D1C;
    static final int GL_SOURCE0_RGB = 0x8580;
    static final int GL_SOURCE1_RGB = 0x8581;
    static final int GL_SOURCE2_RGB = 0x8582;
    static final int GL_SOURCE0_ALPHA = 0x8588;
    static final int GL_SOURCE1_ALPHA = 0x8589;
    static final int GL_SOURCE2_ALPHA = 0x858A;
    static final int GL_OPERAND0_RGB = 0x8590;
    static final int GL_OPERAND1_RGB = 0x8591;
    static final int GL_OPERAND2_RGB = 0x8592;
    static final int GL_OPERAND0_ALPHA = 0x8598;
    static final int GL_OPERAND1_ALPHA = 0x8599;
    static final int GL_OPERAND2_ALPHA = 0x859A;
    static final int GL_CONSTANT = 0x8576;
    static final int GL_PREVIOUS = 0x8578;

    // texture coordinate generation
    static final int GL_S = 0x2000;
    static final int GL_T = 0x2001;
    static final int GL_R = 0x2002;
    static final int GL_Q = 0x2003;
    static final int GL_TEXTURE_GEN_MODE = 0x2500;
    static final int GL_OBJECT_PLANE = 0x2501;
    static final int GL_EYE_PLANE = 0x2502;
    static final int GL_EYE_LINEAR = 0x2400;
    static final int GL_OBJECT_LINEAR = 0x2401;
    static final int GL_SPHERE_MAP = 0x2402;
    static final int GL_NORMAL_MAP = 0x8511;
    static final int GL_REFLECTION_MAP = 0x8512;

    // enable caps
    static final int GL_POINT_SMOOTH = 0x0B10;
    static final int GL_LINE_SMOOTH = 0x0B20;
    static final int GL_LINE_STIPPLE = 0x0B24;
    static final int GL_POLYGON_SMOOTH = 0x0B41;
    static final int GL_POLYGON_STIPPLE = 0x0B42;
    static final int GL_CULL_FACE = 0x0B44;
    static final int GL_LIGHTING = 0x0B50;
    static final int GL_COLOR_MATERIAL = 0x0B57;
    static final int GL_FOG = 0x0B60;
    static final int GL_FOG_INDEX = 0x0B61;
    static final int GL_FOG_DENSITY = 0x0B62;
    static final int GL_FOG_START = 0x0B63;
    static final int GL_FOG_END = 0x0B64;
    static final int GL_FOG_MODE = 0x0B65;
    static final int GL_FOG_COLOR = 0x0B66;
    static final int GL_DEPTH_TEST = 0x0B71;
    static final int GL_STENCIL_TEST = 0x0B90;
    static final int GL_NORMALIZE = 0x0BA1;
    static final int GL_ALPHA_TEST = 0x0BC0;
    static final int GL_DITHER = 0x0BD0;
    static final int GL_INDEX_LOGIC_OP = 0x0BF1;
    static final int GL_COLOR_LOGIC_OP = 0x0BF2;
    static final int GL_SCISSOR_TEST = 0x0C11;
    static final int GL_TEXTURE_GEN_S = 0x0C60;
    static final int GL_TEXTURE_GEN_T = 0x0C61;
    static final int GL_TEXTURE_GEN_R = 0x0C62;
    static final int GL_TEXTURE_GEN_Q = 0x0C63;
    static final int GL_AUTO_NORMAL = 0x0D80;
    static final int GL_TEXTURE_1D = 0x0DE0;
    static final int GL_TEXTURE_2D = 0x0DE1;
    static final int GL_POLYGON_OFFSET_POINT = 0x2A01;
    static final int GL_POLYGON_OFFSET_LINE = 0x2A02;
    static final int GL_CLIP_PLANE0 = 0x3000;
    static final int GL_LIGHT0 = 0x4000;
    static final int GL_POLYGON_OFFSET_FILL = 0x8037;
    static final int GL_RESCALE_NORMAL = 0x803A;
    static final int GL_TEXTURE_3D = 0x806F;
    static final int GL_MULTISAMPLE = 0x809D;
    static final int GL_SAMPLE_ALPHA_TO_COVERAGE = 0x809E;
    static final int GL_SAMPLE_ALPHA_TO_ONE = 0x809F;
    static final int GL_SAMPLE_COVERAGE = 0x80A0;
    static final int GL_TEXTURE_CUBE_MAP = 0x8513;

    // hints
    static final int GL_PERSPECTIVE_CORRECTION_HINT = 0x0C50;
    static final int GL_POINT_SMOOTH_HINT = 0x0C51;
    static final int GL_LINE_SMOOTH_HINT = 0x0C52;
    static final int GL_POLYGON_SMOOTH_HINT = 0x0C53;
    static final int GL_FOG_HINT = 0x0C54;
    static final int GL_GENERATE_MIPMAP_HINT = 0x8192;
    static final int GL_TEXTURE_COMPRESSION_HINT = 0x84EF;
    static final int GL_DONT_CARE = 0x1100;
    static final int GL_FASTEST = 0x1101;
    static final int GL_NICEST = 0x1102;

    // glPushAttrib masks
    static final int GL_CURRENT_BIT = 0x00000001;
    static final int GL_POINT_BIT = 0x00000002;
    static final int GL_LINE_BIT = 0x00000004;
    static final int GL_POLYGON_BIT = 0x00000008;
    static final int GL_POLYGON_STIPPLE_BIT = 0x00000010;
    static final int GL_PIXEL_MODE_BIT = 0x00000020;
    static final int GL_LIGHTING_BIT = 0x00000040;
    static final int GL_FOG_BIT = 0x00000080;
    static final int GL_DEPTH_BUFFER_BIT = 0x00000100;
    static final int GL_ACCUM_BUFFER_BIT = 0x00000200;
    static final int GL_STENCIL_BUFFER_BIT = 0x00000400;
    static final int GL_VIEWPORT_BIT = 0x00000800;
    static final int GL_TRANSFORM_BIT = 0x00001000;
    static final int GL_ENABLE_BIT = 0x00002000;
    static final int GL_COLOR_BUFFER_BIT = 0x00004000;
    static final int GL_HINT_BIT = 0x00008000;
    static final int GL_EVAL_BIT = 0x00010000;
    static final int GL_LIST_BIT = 0x00020000;
    static final int GL_TEXTURE_BIT = 0x00040000;
    static final int GL_SCISSOR_BIT = 0x00080000;
    static final int GL_ALL_ATTRIB_BITS = 0x000FFFFF;
}
