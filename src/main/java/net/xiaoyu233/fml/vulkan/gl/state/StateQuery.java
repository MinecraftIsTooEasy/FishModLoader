package net.xiaoyu233.fml.vulkan.gl.state;

import net.xiaoyu233.fml.vulkan.core.DeviceInfo;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import org.lwjgl.vulkan.VK10;

import static net.xiaoyu233.fml.vulkan.gl.state.GLConst.*;

/**
 * Values of the GL state variables behind glGet* / glGetLight / glGetMaterial / glGetTexEnv / glGetTexGen. Every query writes
 * its values (as doubles, exact for floats, ints and enums) into a caller supplied scratch array and returns the number of
 * values, or -1 when the pname is not owned by the state machine. Allocation free.
 */
final class StateQuery {
    private StateQuery() {
    }

    // pnames that only exist here (the enums shared with glEnable / the attrib masks live in GLConst)
    private static final int GL_CURRENT_COLOR = 0x0B00;
    private static final int GL_CURRENT_INDEX = 0x0B01;
    private static final int GL_CURRENT_NORMAL = 0x0B02;
    private static final int GL_CURRENT_TEXTURE_COORDS = 0x0B03;
    private static final int GL_CURRENT_RASTER_COLOR = 0x0B04;
    private static final int GL_CURRENT_RASTER_INDEX = 0x0B05;
    private static final int GL_CURRENT_RASTER_TEXTURE_COORDS = 0x0B06;
    private static final int GL_CURRENT_RASTER_POSITION = 0x0B07;
    private static final int GL_CURRENT_RASTER_POSITION_VALID = 0x0B08;
    private static final int GL_CURRENT_RASTER_DISTANCE = 0x0B09;
    private static final int GL_POINT_SIZE = 0x0B11;
    private static final int GL_POINT_SIZE_RANGE = 0x0B12;
    private static final int GL_POINT_SIZE_GRANULARITY = 0x0B13;
    private static final int GL_LINE_WIDTH = 0x0B21;
    private static final int GL_LINE_WIDTH_RANGE = 0x0B22;
    private static final int GL_LINE_WIDTH_GRANULARITY = 0x0B23;
    private static final int GL_MAX_LIST_NESTING = 0x0B31;
    private static final int GL_POLYGON_MODE = 0x0B40;
    private static final int GL_CULL_FACE_MODE = 0x0B45;
    private static final int GL_FRONT_FACE = 0x0B46;
    private static final int GL_SHADE_MODEL = 0x0B54;
    private static final int GL_COLOR_MATERIAL_FACE = 0x0B55;
    private static final int GL_COLOR_MATERIAL_PARAMETER = 0x0B56;
    private static final int GL_DEPTH_RANGE = 0x0B70;
    private static final int GL_DEPTH_WRITEMASK = 0x0B72;
    private static final int GL_DEPTH_CLEAR_VALUE = 0x0B73;
    private static final int GL_DEPTH_FUNC = 0x0B74;
    private static final int GL_ACCUM_CLEAR_VALUE = 0x0B80;
    private static final int GL_STENCIL_CLEAR_VALUE = 0x0B91;
    private static final int GL_STENCIL_FUNC = 0x0B92;
    private static final int GL_STENCIL_VALUE_MASK = 0x0B93;
    private static final int GL_STENCIL_FAIL = 0x0B94;
    private static final int GL_STENCIL_PASS_DEPTH_FAIL = 0x0B95;
    private static final int GL_STENCIL_PASS_DEPTH_PASS = 0x0B96;
    private static final int GL_STENCIL_REF = 0x0B97;
    private static final int GL_STENCIL_WRITEMASK = 0x0B98;
    private static final int GL_MATRIX_MODE = 0x0BA0;
    private static final int GL_VIEWPORT = 0x0BA2;
    private static final int GL_MODELVIEW_STACK_DEPTH = 0x0BA3;
    private static final int GL_PROJECTION_STACK_DEPTH = 0x0BA4;
    private static final int GL_TEXTURE_STACK_DEPTH = 0x0BA5;
    private static final int GL_MODELVIEW_MATRIX = 0x0BA6;
    private static final int GL_PROJECTION_MATRIX = 0x0BA7;
    private static final int GL_TEXTURE_MATRIX = 0x0BA8;
    private static final int GL_ATTRIB_STACK_DEPTH = 0x0BB0;
    private static final int GL_ALPHA_TEST_FUNC = 0x0BC1;
    private static final int GL_ALPHA_TEST_REF = 0x0BC2;
    private static final int GL_BLEND_DST = 0x0BE0;
    private static final int GL_BLEND_SRC = 0x0BE1;
    private static final int GL_LOGIC_OP_MODE = 0x0BF0;
    private static final int GL_AUX_BUFFERS = 0x0C00;
    private static final int GL_DRAW_BUFFER = 0x0C01;
    private static final int GL_READ_BUFFER = 0x0C02;
    private static final int GL_SCISSOR_BOX = 0x0C10;
    private static final int GL_INDEX_CLEAR_VALUE = 0x0C20;
    private static final int GL_INDEX_WRITEMASK = 0x0C21;
    private static final int GL_COLOR_CLEAR_VALUE = 0x0C22;
    private static final int GL_COLOR_WRITEMASK = 0x0C23;
    private static final int GL_INDEX_MODE = 0x0C30;
    private static final int GL_RGBA_MODE = 0x0C31;
    private static final int GL_DOUBLEBUFFER = 0x0C32;
    private static final int GL_STEREO = 0x0C33;
    private static final int GL_RENDER_MODE = 0x0C40;
    private static final int GL_MAX_EVAL_ORDER = 0x0D30;
    private static final int GL_MAX_LIGHTS = 0x0D31;
    private static final int GL_MAX_CLIP_PLANES = 0x0D32;
    private static final int GL_MAX_PIXEL_MAP_TABLE = 0x0D34;
    private static final int GL_MAX_ATTRIB_STACK_DEPTH = 0x0D35;
    private static final int GL_MAX_MODELVIEW_STACK_DEPTH = 0x0D36;
    private static final int GL_MAX_NAME_STACK_DEPTH = 0x0D37;
    private static final int GL_MAX_PROJECTION_STACK_DEPTH = 0x0D38;
    private static final int GL_MAX_TEXTURE_STACK_DEPTH = 0x0D39;
    private static final int GL_MAX_VIEWPORT_DIMS = 0x0D3A;
    private static final int GL_SUBPIXEL_BITS = 0x0D50;
    private static final int GL_INDEX_BITS = 0x0D51;
    private static final int GL_RED_BITS = 0x0D52;
    private static final int GL_GREEN_BITS = 0x0D53;
    private static final int GL_BLUE_BITS = 0x0D54;
    private static final int GL_ALPHA_BITS = 0x0D55;
    private static final int GL_DEPTH_BITS = 0x0D56;
    private static final int GL_STENCIL_BITS = 0x0D57;
    private static final int GL_ACCUM_RED_BITS = 0x0D58;
    private static final int GL_ACCUM_GREEN_BITS = 0x0D59;
    private static final int GL_ACCUM_BLUE_BITS = 0x0D5A;
    private static final int GL_ACCUM_ALPHA_BITS = 0x0D5B;
    private static final int GL_NAME_STACK_DEPTH = 0x0D70;
    private static final int GL_POLYGON_OFFSET_UNITS = 0x2A00;
    private static final int GL_BLEND_COLOR = 0x8005;
    private static final int GL_BLEND_EQUATION = 0x8009;
    private static final int GL_POLYGON_OFFSET_FACTOR = 0x8038;
    private static final int GL_MAX_3D_TEXTURE_SIZE = 0x8073;
    private static final int GL_SAMPLE_BUFFERS = 0x80A8;
    private static final int GL_SAMPLES = 0x80A9;
    private static final int GL_BLEND_DST_RGB = 0x80C8;
    private static final int GL_BLEND_SRC_RGB = 0x80C9;
    private static final int GL_BLEND_DST_ALPHA = 0x80CA;
    private static final int GL_BLEND_SRC_ALPHA = 0x80CB;
    private static final int GL_MAX_ELEMENTS_VERTICES = 0x80E8;
    private static final int GL_MAX_ELEMENTS_INDICES = 0x80E9;
    private static final int GL_ALIASED_POINT_SIZE_RANGE = 0x846D;
    private static final int GL_ALIASED_LINE_WIDTH_RANGE = 0x846E;
    private static final int GL_ACTIVE_TEXTURE = 0x84E0;
    private static final int GL_MAX_TEXTURE_UNITS = 0x84E2;
    private static final int GL_TRANSPOSE_MODELVIEW_MATRIX = 0x84E3;
    private static final int GL_TRANSPOSE_PROJECTION_MATRIX = 0x84E4;
    private static final int GL_TRANSPOSE_TEXTURE_MATRIX = 0x84E5;
    private static final int GL_MAX_TEXTURE_LOD_BIAS = 0x84FD;
    private static final int GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FF;
    private static final int GL_MAX_CUBE_MAP_TEXTURE_SIZE = 0x851C;
    // GL 2.0 limits probed by the game (Minecraft.addServerTypeToSnooper): answered with 0 = "no shader support", no error
    private static final int GL_MAX_VERTEX_ATTRIBS = 0x8869;
    private static final int GL_MAX_TEXTURE_COORDS = 0x8871;
    private static final int GL_MAX_TEXTURE_IMAGE_UNITS = 0x8872;
    private static final int GL_MAX_DRAW_BUFFERS = 0x8824;
    private static final int GL_MAX_FRAGMENT_UNIFORM_COMPONENTS = 0x8B49;
    private static final int GL_MAX_VERTEX_UNIFORM_COMPONENTS = 0x8B4A;
    private static final int GL_MAX_VARYING_FLOATS = 0x8B4B;
    private static final int GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS = 0x8B4C;
    private static final int GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS = 0x8B4D;

    /** Colors, normals, clear values and depth range: glGetInteger maps these linearly onto the int range (1.0 = max int). */
    static boolean isColorLike(int pname) {
        switch (pname) {
            case GL_CURRENT_COLOR:
            case GL_CURRENT_NORMAL:
            case GL_COLOR_CLEAR_VALUE:
            case GL_DEPTH_CLEAR_VALUE:
            case GL_DEPTH_RANGE:
            case GL_FOG_COLOR:
            case GL_LIGHT_MODEL_AMBIENT:
            case GL_BLEND_COLOR:
            case GL_ACCUM_CLEAR_VALUE:
            case GL_ALPHA_TEST_REF:
                return true;
            default:
                return false;
        }
    }

    private static int matrix(float[] m, double[] o, boolean transposed) {
        if (transposed) {
            for (int c = 0; c < 4; c++) {
                for (int r = 0; r < 4; r++) {
                    o[r * 4 + c] = m[c * 4 + r];
                }
            }
        } else {
            for (int i = 0; i < 16; i++) {
                o[i] = m[i];
            }
        }
        return 16;
    }

    private static int vec(float[] v, int n, double[] o) {
        for (int i = 0; i < n; i++) {
            o[i] = v[i];
        }
        return n;
    }

    private static int one(double v, double[] o) {
        o[0] = v;
        return 1;
    }

    private static int bool(boolean b, double[] o) {
        o[0] = b ? 1 : 0;
        return 1;
    }

    private static int two(double a, double b, double[] o) {
        o[0] = a;
        o[1] = b;
        return 2;
    }

    private static int four(double a, double b, double c, double d, double[] o) {
        o[0] = a;
        o[1] = b;
        o[2] = c;
        o[3] = d;
        return 4;
    }

    private static int bits(int vkFormat, int channel) {
        // channel: 0 color (R/G/B/A all 8 for the supported offscreen formats), 1 depth, 2 stencil
        switch (channel) {
            case 0:
                return 8;
            case 1:
                switch (vkFormat) {
                    case VK10.VK_FORMAT_D16_UNORM:
                    case VK10.VK_FORMAT_D16_UNORM_S8_UINT:
                        return 16;
                    case VK10.VK_FORMAT_D32_SFLOAT:
                    case VK10.VK_FORMAT_D32_SFLOAT_S8_UINT:
                        return 32;
                    case VK10.VK_FORMAT_X8_D24_UNORM_PACK32:
                    case VK10.VK_FORMAT_D24_UNORM_S8_UINT:
                    default:
                        return 24;
                }
            default:
                switch (vkFormat) {
                    case VK10.VK_FORMAT_D16_UNORM_S8_UINT:
                    case VK10.VK_FORMAT_D24_UNORM_S8_UINT:
                    case VK10.VK_FORMAT_D32_SFLOAT_S8_UINT:
                        return 8;
                    default:
                        return 0;
                }
        }
    }

    /** The values of {@code pname}, the number of values, or -1 when the pname is not a state variable of this class. */
    static int get(GLState s, int pname, double[] o) {
        switch (pname) {
            // --- matrices and stacks
            case GL_MODELVIEW_MATRIX:
                return matrix(s.modelView.top(), o, false);
            case GL_PROJECTION_MATRIX:
                return matrix(s.projection.top(), o, false);
            case GL_TEXTURE_MATRIX:
                return matrix(s.texMatrix[s.activeTextureUnit].top(), o, false);
            case GL_TRANSPOSE_MODELVIEW_MATRIX:
                return matrix(s.modelView.top(), o, true);
            case GL_TRANSPOSE_PROJECTION_MATRIX:
                return matrix(s.projection.top(), o, true);
            case GL_TRANSPOSE_TEXTURE_MATRIX:
                return matrix(s.texMatrix[s.activeTextureUnit].top(), o, true);
            case GL_MATRIX_MODE:
                return one(s.matrixMode, o);
            case GL_MODELVIEW_STACK_DEPTH:
                return one(s.modelView.depth(), o);
            case GL_PROJECTION_STACK_DEPTH:
                return one(s.projection.depth(), o);
            case GL_TEXTURE_STACK_DEPTH:
                return one(s.texMatrix[s.activeTextureUnit].depth(), o);
            case GL_MAX_MODELVIEW_STACK_DEPTH:
                return one(s.modelView.maxDepth(), o);
            case GL_MAX_PROJECTION_STACK_DEPTH:
                return one(s.projection.maxDepth(), o);
            case GL_MAX_TEXTURE_STACK_DEPTH:
                return one(s.texMatrix[0].maxDepth(), o);
            case GL_ATTRIB_STACK_DEPTH:
                return one(s.attribStack.depth(), o);
            case GL_MAX_ATTRIB_STACK_DEPTH:
                return one(GLState.ATTRIB_STACK_DEPTH, o);

            // --- current values
            case GL_CURRENT_COLOR:
                return vec(s.currentColor, 4, o);
            case GL_CURRENT_NORMAL:
                return vec(s.currentNormal, 3, o);
            case GL_CURRENT_TEXTURE_COORDS:
                return vec(s.currentTexCoord[s.activeTextureUnit], 4, o);
            case GL_CURRENT_INDEX:
                return one(1, o);
            case GL_CURRENT_RASTER_COLOR:
                return four(1, 1, 1, 1, o);
            case GL_CURRENT_RASTER_POSITION:
            case GL_CURRENT_RASTER_TEXTURE_COORDS:
                return four(0, 0, 0, 1, o);
            case GL_CURRENT_RASTER_INDEX:
            case GL_CURRENT_RASTER_DISTANCE:
                return one(0, o);
            case GL_CURRENT_RASTER_POSITION_VALID:
                return bool(true, o);

            // --- viewport, scissor, clear
            case GL_VIEWPORT:
                return four(s.viewport[0], s.viewport[1], s.viewport[2], s.viewport[3], o);
            case GL_SCISSOR_BOX:
                return four(s.scissorBox[0], s.scissorBox[1], s.scissorBox[2], s.scissorBox[3], o);
            case GL_MAX_VIEWPORT_DIMS: {
                final DeviceInfo di = s.deviceInfoOrNull();
                final int max = di != null ? Math.max(1, di.maxImageDimension2D()) : 16384;
                return two(max, max, o);
            }
            case GL_DEPTH_RANGE:
                return two(s.depthNear, s.depthFar, o);
            case GL_COLOR_CLEAR_VALUE:
                return vec(s.clearColor, 4, o);
            case GL_DEPTH_CLEAR_VALUE:
                return one(s.clearDepth, o);
            case GL_STENCIL_CLEAR_VALUE:
                return one(s.clearStencil, o);
            case GL_ACCUM_CLEAR_VALUE:
                return four(0, 0, 0, 0, o);
            case GL_INDEX_CLEAR_VALUE:
            case GL_INDEX_WRITEMASK:
                return one(0, o);

            // --- color buffer
            case GL_COLOR_WRITEMASK:
                return four((s.colorMask & VK10.VK_COLOR_COMPONENT_R_BIT) != 0 ? 1 : 0,
                        (s.colorMask & VK10.VK_COLOR_COMPONENT_G_BIT) != 0 ? 1 : 0,
                        (s.colorMask & VK10.VK_COLOR_COMPONENT_B_BIT) != 0 ? 1 : 0,
                        (s.colorMask & VK10.VK_COLOR_COMPONENT_A_BIT) != 0 ? 1 : 0, o);
            case GL_DRAW_BUFFER:
                return one(s.drawBuffer, o);
            case GL_READ_BUFFER:
                return one(s.readBuffer, o);
            case GL_BLEND_SRC:
            case GL_BLEND_SRC_RGB:
                return one(s.blendSrcRgb, o);
            case GL_BLEND_DST:
            case GL_BLEND_DST_RGB:
                return one(s.blendDstRgb, o);
            case GL_BLEND_SRC_ALPHA:
                return one(s.blendSrcAlpha, o);
            case GL_BLEND_DST_ALPHA:
                return one(s.blendDstAlpha, o);
            case GL_BLEND_EQUATION:
                return one(s.blendEqRgb, o);
            case 0x883D: // GL_BLEND_EQUATION_ALPHA
                return one(s.blendEqAlpha, o);
            case GL_BLEND_COLOR:
                return vec(s.blendColor, 4, o);
            case GL_LOGIC_OP_MODE:
                return one(s.logicOp, o);
            case GL_ALPHA_TEST_FUNC:
                return one(s.alphaFunc, o);
            case GL_ALPHA_TEST_REF:
                return one(s.alphaRef, o);

            // --- depth / stencil
            case GL_DEPTH_FUNC:
                return one(s.depthFunc, o);
            case GL_DEPTH_WRITEMASK:
                return bool(s.depthMask, o);
            case GL_STENCIL_FUNC:
                return one(s.stencilFunc, o);
            case GL_STENCIL_VALUE_MASK:
                return one(s.stencilValueMask, o);
            case GL_STENCIL_FAIL:
                return one(s.stencilFail, o);
            case GL_STENCIL_PASS_DEPTH_FAIL:
                return one(s.stencilZFail, o);
            case GL_STENCIL_PASS_DEPTH_PASS:
                return one(s.stencilZPass, o);
            case GL_STENCIL_REF:
                return one(s.stencilRef, o);
            case GL_STENCIL_WRITEMASK:
                return one(s.stencilWriteMask, o);

            // --- rasterization
            case GL_CULL_FACE_MODE:
                return one(s.cullFaceMode, o);
            case GL_FRONT_FACE:
                return one(s.frontFace, o);
            case GL_POLYGON_MODE:
                return two(s.polyModeFront, s.polyModeBack, o);
            case GL_POLYGON_OFFSET_FACTOR:
                return one(s.polyOffsetFactor, o);
            case GL_POLYGON_OFFSET_UNITS:
                return one(s.polyOffsetUnits, o);
            case GL_SHADE_MODEL:
                return one(s.shadeModel, o);
            case GL_LINE_WIDTH:
                return one(s.lineWidth, o);
            case GL_POINT_SIZE:
                return one(s.pointSize, o);
            case GL_LINE_WIDTH_RANGE:
            case GL_ALIASED_LINE_WIDTH_RANGE: {
                final DeviceInfo di = s.deviceInfoOrNull();
                return di != null ? two(di.lineWidthRange()[0], di.lineWidthRange()[1], o) : two(1, 1, o);
            }
            case GL_POINT_SIZE_RANGE:
            case GL_ALIASED_POINT_SIZE_RANGE: {
                final DeviceInfo di = s.deviceInfoOrNull();
                return di != null ? two(di.pointSizeRange()[0], di.pointSizeRange()[1], o) : two(1, 1, o);
            }
            case GL_LINE_WIDTH_GRANULARITY:
            case GL_POINT_SIZE_GRANULARITY:
                return one(0.125, o);

            // --- lighting, fog
            case GL_LIGHT_MODEL_AMBIENT:
                return vec(s.lightModelAmbient, 4, o);
            case GL_LIGHT_MODEL_LOCAL_VIEWER:
                return bool(s.lightModelLocalViewer, o);
            case GL_LIGHT_MODEL_TWO_SIDE:
                return bool(s.lightModelTwoSide, o);
            case GL_LIGHT_MODEL_COLOR_CONTROL:
                return one(s.lightModelColorControl, o);
            case GL_COLOR_MATERIAL_FACE:
                return one(s.colorMaterialFace, o);
            case GL_COLOR_MATERIAL_PARAMETER:
                return one(s.colorMaterialMode, o);
            case GL_FOG_MODE:
                return one(s.fog.mode, o);
            case GL_FOG_DENSITY:
                return one(s.fog.density, o);
            case GL_FOG_START:
                return one(s.fog.start, o);
            case GL_FOG_END:
                return one(s.fog.end, o);
            case GL_FOG_INDEX:
                return one(s.fog.index, o);
            case GL_FOG_COLOR:
                return vec(s.fog.color, 4, o);
            case GL_FOG_DISTANCE_MODE_NV:
                return one(s.fog.distanceMode, o);

            // --- texture units
            case GL_ACTIVE_TEXTURE:
                return one(0x84C0 + s.activeTextureUnit, o);
            case GL_MAX_TEXTURE_UNITS:
                return one(GLState.MAX_TEXTURE_UNITS, o);
            case GL_MAX_LIGHTS:
                return one(GLState.MAX_LIGHTS, o);
            case GL_MAX_CLIP_PLANES:
                return one(GLState.MAX_CLIP_PLANES, o);
            case GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT: {
                final DeviceInfo di = s.deviceInfoOrNull();
                if (di == null || !di.samplerAnisotropy()) {
                    return -1;
                }
                return one(di.maxAnisotropy(), o);
            }
            case GL_MAX_TEXTURE_LOD_BIAS:
                return one(15.0, o);
            case GL_MAX_3D_TEXTURE_SIZE:
            case GL_MAX_CUBE_MAP_TEXTURE_SIZE:
                return one(0, o); // neither 3D textures nor cube maps are supported

            // --- hints
            case GL_PERSPECTIVE_CORRECTION_HINT:
            case GL_POINT_SMOOTH_HINT:
            case GL_LINE_SMOOTH_HINT:
            case GL_POLYGON_SMOOTH_HINT:
            case GL_FOG_HINT:
            case GL_GENERATE_MIPMAP_HINT:
            case GL_TEXTURE_COMPRESSION_HINT:
                return one(s.hints[GLState.hintIndex(pname)], o);

            // --- implementation limits and framebuffer format
            case GL_MAX_LIST_NESTING:
                return one(64, o);
            case GL_MAX_EVAL_ORDER:
                return one(8, o);
            case GL_MAX_PIXEL_MAP_TABLE:
                return one(32, o);
            case GL_MAX_NAME_STACK_DEPTH:
                return one(128, o);
            case GL_NAME_STACK_DEPTH:
                return one(0, o);
            case GL_MAX_ELEMENTS_VERTICES:
            case GL_MAX_ELEMENTS_INDICES:
                return one(1 << 20, o);
            case GL_SUBPIXEL_BITS:
                return one(8, o);
            case GL_RED_BITS:
            case GL_GREEN_BITS:
            case GL_BLUE_BITS:
            case GL_ALPHA_BITS:
                return one(bits(0, 0), o);
            case GL_DEPTH_BITS:
                return one(bits(RenderCore.isInitialized() ? RenderCore.depthFormat() : 0, 1), o);
            case GL_STENCIL_BITS:
                return one(bits(RenderCore.isInitialized() ? RenderCore.depthFormat() : 0, 2), o);
            case GL_INDEX_BITS:
            case GL_ACCUM_RED_BITS:
            case GL_ACCUM_GREEN_BITS:
            case GL_ACCUM_BLUE_BITS:
            case GL_ACCUM_ALPHA_BITS:
            case GL_AUX_BUFFERS:
            case GL_SAMPLES:
            case GL_SAMPLE_BUFFERS:
                return one(0, o);
            case GL_RGBA_MODE:
            case GL_DOUBLEBUFFER:
                return bool(true, o);
            case GL_INDEX_MODE:
            case GL_STEREO:
                return bool(false, o);
            case GL_RENDER_MODE:
                return one(0x1C00, o); // GL_RENDER
            case GL_MAX_VERTEX_ATTRIBS:
            case GL_MAX_TEXTURE_COORDS:
            case GL_MAX_TEXTURE_IMAGE_UNITS:
            case GL_MAX_DRAW_BUFFERS:
            case GL_MAX_FRAGMENT_UNIFORM_COMPONENTS:
            case GL_MAX_VERTEX_UNIFORM_COMPONENTS:
            case GL_MAX_VARYING_FLOATS:
            case GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS:
            case GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS:
                return one(0, o);
            default:
                break;
        }
        // plain capability flags (glGetBoolean(GL_DEPTH_TEST) etc.)
        if (GLState.isKnownCap(pname)) {
            return bool(s.isEnabled(pname), o);
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // glGetLight / glGetMaterial / glGetTexEnv / glGetTexGen
    // ---------------------------------------------------------------------------------------------------------------

    static int light(GLState s, int index, int pname, float[] o) {
        if (index < 0 || index >= GLState.MAX_LIGHTS) {
            GLState.error(GL_INVALID_ENUM);
            return -1;
        }
        final LightState l = s.lights[index];
        switch (pname) {
            case GL_AMBIENT:
                return fvec(l.ambient, 4, o);
            case GL_DIFFUSE:
                return fvec(l.diffuse, 4, o);
            case GL_SPECULAR:
                return fvec(l.specular, 4, o);
            case GL_POSITION:
                return fvec(l.position, 4, o);
            case GL_SPOT_DIRECTION:
                return fvec(l.spotDirection, 3, o);
            case GL_SPOT_EXPONENT:
                return f1(l.spotExponent, o);
            case GL_SPOT_CUTOFF:
                return f1(l.spotCutoff, o);
            case GL_CONSTANT_ATTENUATION:
                return f1(l.constantAttenuation, o);
            case GL_LINEAR_ATTENUATION:
                return f1(l.linearAttenuation, o);
            case GL_QUADRATIC_ATTENUATION:
                return f1(l.quadraticAttenuation, o);
            default:
                GLState.error(GL_INVALID_ENUM);
                return -1;
        }
    }

    static int material(GLState s, int face, int pname, float[] o) {
        if (face != GL_FRONT && face != GL_BACK) {
            GLState.error(GL_INVALID_ENUM);
            return -1;
        }
        final MaterialState m = face == GL_FRONT ? s.material : s.materialBack;
        switch (pname) {
            case GL_AMBIENT:
                return fvec(m.ambient, 4, o);
            case GL_DIFFUSE:
                return fvec(m.diffuse, 4, o);
            case GL_SPECULAR:
                return fvec(m.specular, 4, o);
            case GL_EMISSION:
                return fvec(m.emission, 4, o);
            case GL_SHININESS:
                return f1(m.shininess, o);
            case GL_COLOR_INDEXES:
                o[0] = 0f;
                o[1] = 1f;
                o[2] = 1f;
                return 3;
            default:
                GLState.error(GL_INVALID_ENUM);
                return -1;
        }
    }

    static int texEnv(GLState s, int target, int pname, float[] o) {
        if (GLState.texEnvParamCount(target, pname) == 0) {
            GLState.error(GL_INVALID_ENUM);
            return -1;
        }
        final TexUnitState u = s.texUnits[s.activeTextureUnit];
        if (target == GL_TEXTURE_FILTER_CONTROL) {
            return f1(u.lodBias, o);
        }
        if (target == GL_POINT_SPRITE) {
            return f1(u.coordReplace ? 1f : 0f, o);
        }
        switch (pname) {
            case GL_TEXTURE_ENV_MODE:
                return f1(u.envMode, o);
            case GL_TEXTURE_ENV_COLOR:
                return fvec(u.envColor, 4, o);
            case GL_COMBINE_RGB:
                return f1(u.combineRgb, o);
            case GL_COMBINE_ALPHA:
                return f1(u.combineAlpha, o);
            case GL_RGB_SCALE:
                return f1(u.rgbScale, o);
            case GL_ALPHA_SCALE:
                return f1(u.alphaScale, o);
            case GL_SOURCE0_RGB:
                return f1(u.sourceRgb[0], o);
            case GL_SOURCE1_RGB:
                return f1(u.sourceRgb[1], o);
            case GL_SOURCE2_RGB:
                return f1(u.sourceRgb[2], o);
            case GL_SOURCE0_ALPHA:
                return f1(u.sourceAlpha[0], o);
            case GL_SOURCE1_ALPHA:
                return f1(u.sourceAlpha[1], o);
            case GL_SOURCE2_ALPHA:
                return f1(u.sourceAlpha[2], o);
            case GL_OPERAND0_RGB:
                return f1(u.operandRgb[0], o);
            case GL_OPERAND1_RGB:
                return f1(u.operandRgb[1], o);
            case GL_OPERAND2_RGB:
                return f1(u.operandRgb[2], o);
            case GL_OPERAND0_ALPHA:
                return f1(u.operandAlpha[0], o);
            case GL_OPERAND1_ALPHA:
                return f1(u.operandAlpha[1], o);
            case GL_OPERAND2_ALPHA:
                return f1(u.operandAlpha[2], o);
            default:
                GLState.error(GL_INVALID_ENUM);
                return -1;
        }
    }

    static int texGen(GLState s, int coord, int pname, float[] o) {
        final int c = coord - GL_S;
        if (c < 0 || c > 3 || GLState.texGenParamCount(pname) == 0) {
            GLState.error(GL_INVALID_ENUM);
            return -1;
        }
        final TexUnitState u = s.texUnits[s.activeTextureUnit];
        switch (pname) {
            case GL_TEXTURE_GEN_MODE:
                return f1(u.genMode[c], o);
            case GL_OBJECT_PLANE:
                return fvec(u.objectPlane[c], 4, o);
            case GL_EYE_PLANE:
                return fvec(u.eyePlane[c], 4, o);
            default:
                GLState.error(GL_INVALID_ENUM);
                return -1;
        }
    }

    private static int fvec(float[] v, int n, float[] o) {
        System.arraycopy(v, 0, o, 0, n);
        return n;
    }

    private static int f1(float v, float[] o) {
        o[0] = v;
        return 1;
    }
}
