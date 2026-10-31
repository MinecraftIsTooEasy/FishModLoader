package net.xiaoyu233.fml.vulkan.pipeline;

import org.lwjgl.vulkan.VK10;

/**
 * Bit layout of the {@code long} pipeline key (../docs/ARCHITECTURE.md (outside the repo) section 5.4). Every field stores the
 * <b>Vulkan enum value</b>. D1 fills the state fields, D2 adds the topology; bits 47-63 are reserved (0).
 *
 * <pre>
 * bit 0        blendEnable                          bits 1-5    srcColorBlendFactor   bits 6-10   dstColorBlendFactor
 * bits 11-15   srcAlphaBlendFactor                  bits 16-20  dstAlphaBlendFactor    bits 21-23  colorBlendOp
 * bits 24-26   alphaBlendOp                         bit 27      depthTestEnable        bit 28      depthWriteEnable
 * bits 29-31   depthCompareOp                       bits 32-33  cullMode               bit 34      frontFaceClockwise
 * bits 35-38   colorWriteMask                       bits 39-40  polygonMode            bits 41-43  topology
 * bit 44       depthBiasEnable                      bit 45      flatShading            bit 46      alphaTest
 * </pre>
 *
 * Each field has an id (for {@link #set}/{@link #get}/{@link #mask}), a {@code _SHIFT} and a {@code _WIDTH}.
 * Front face: with the negative-height viewport GL CCW maps to VK_FRONT_FACE_COUNTER_CLOCKWISE, i.e. bit 34 = 0.
 */
public final class PipelineKey {
    private PipelineKey() {
    }

    // field ids
    public static final int BLEND_ENABLE = 0;
    public static final int SRC_COLOR_BLEND_FACTOR = 1;
    public static final int DST_COLOR_BLEND_FACTOR = 2;
    public static final int SRC_ALPHA_BLEND_FACTOR = 3;
    public static final int DST_ALPHA_BLEND_FACTOR = 4;
    public static final int COLOR_BLEND_OP = 5;
    public static final int ALPHA_BLEND_OP = 6;
    public static final int DEPTH_TEST_ENABLE = 7;
    public static final int DEPTH_WRITE_ENABLE = 8;
    public static final int DEPTH_COMPARE_OP = 9;
    public static final int CULL_MODE = 10;
    public static final int FRONT_FACE_CLOCKWISE = 11;
    public static final int COLOR_WRITE_MASK = 12;
    public static final int POLYGON_MODE = 13;
    public static final int TOPOLOGY = 14;
    public static final int DEPTH_BIAS_ENABLE = 15;
    public static final int FLAT_SHADING = 16;
    public static final int ALPHA_TEST = 17;
    public static final int FIELD_COUNT = 18;

    // shifts
    public static final int BLEND_ENABLE_SHIFT = 0;
    public static final int SRC_COLOR_BLEND_FACTOR_SHIFT = 1;
    public static final int DST_COLOR_BLEND_FACTOR_SHIFT = 6;
    public static final int SRC_ALPHA_BLEND_FACTOR_SHIFT = 11;
    public static final int DST_ALPHA_BLEND_FACTOR_SHIFT = 16;
    public static final int COLOR_BLEND_OP_SHIFT = 21;
    public static final int ALPHA_BLEND_OP_SHIFT = 24;
    public static final int DEPTH_TEST_ENABLE_SHIFT = 27;
    public static final int DEPTH_WRITE_ENABLE_SHIFT = 28;
    public static final int DEPTH_COMPARE_OP_SHIFT = 29;
    public static final int CULL_MODE_SHIFT = 32;
    public static final int FRONT_FACE_CLOCKWISE_SHIFT = 34;
    public static final int COLOR_WRITE_MASK_SHIFT = 35;
    public static final int POLYGON_MODE_SHIFT = 39;
    public static final int TOPOLOGY_SHIFT = 41;
    public static final int DEPTH_BIAS_ENABLE_SHIFT = 44;
    public static final int FLAT_SHADING_SHIFT = 45;
    public static final int ALPHA_TEST_SHIFT = 46;

    // widths (bits)
    public static final int BLEND_ENABLE_WIDTH = 1;
    public static final int SRC_COLOR_BLEND_FACTOR_WIDTH = 5;
    public static final int DST_COLOR_BLEND_FACTOR_WIDTH = 5;
    public static final int SRC_ALPHA_BLEND_FACTOR_WIDTH = 5;
    public static final int DST_ALPHA_BLEND_FACTOR_WIDTH = 5;
    public static final int COLOR_BLEND_OP_WIDTH = 3;
    public static final int ALPHA_BLEND_OP_WIDTH = 3;
    public static final int DEPTH_TEST_ENABLE_WIDTH = 1;
    public static final int DEPTH_WRITE_ENABLE_WIDTH = 1;
    public static final int DEPTH_COMPARE_OP_WIDTH = 3;
    public static final int CULL_MODE_WIDTH = 2;
    public static final int FRONT_FACE_CLOCKWISE_WIDTH = 1;
    public static final int COLOR_WRITE_MASK_WIDTH = 4;
    public static final int POLYGON_MODE_WIDTH = 2;
    public static final int TOPOLOGY_WIDTH = 3;
    public static final int DEPTH_BIAS_ENABLE_WIDTH = 1;
    public static final int FLAT_SHADING_WIDTH = 1;
    public static final int ALPHA_TEST_WIDTH = 1;

    private static final int[] SHIFTS = {
            BLEND_ENABLE_SHIFT, SRC_COLOR_BLEND_FACTOR_SHIFT, DST_COLOR_BLEND_FACTOR_SHIFT, SRC_ALPHA_BLEND_FACTOR_SHIFT,
            DST_ALPHA_BLEND_FACTOR_SHIFT, COLOR_BLEND_OP_SHIFT, ALPHA_BLEND_OP_SHIFT, DEPTH_TEST_ENABLE_SHIFT,
            DEPTH_WRITE_ENABLE_SHIFT, DEPTH_COMPARE_OP_SHIFT, CULL_MODE_SHIFT, FRONT_FACE_CLOCKWISE_SHIFT,
            COLOR_WRITE_MASK_SHIFT, POLYGON_MODE_SHIFT, TOPOLOGY_SHIFT, DEPTH_BIAS_ENABLE_SHIFT, FLAT_SHADING_SHIFT,
            ALPHA_TEST_SHIFT};
    private static final int[] WIDTHS = {
            BLEND_ENABLE_WIDTH, SRC_COLOR_BLEND_FACTOR_WIDTH, DST_COLOR_BLEND_FACTOR_WIDTH, SRC_ALPHA_BLEND_FACTOR_WIDTH,
            DST_ALPHA_BLEND_FACTOR_WIDTH, COLOR_BLEND_OP_WIDTH, ALPHA_BLEND_OP_WIDTH, DEPTH_TEST_ENABLE_WIDTH,
            DEPTH_WRITE_ENABLE_WIDTH, DEPTH_COMPARE_OP_WIDTH, CULL_MODE_WIDTH, FRONT_FACE_CLOCKWISE_WIDTH,
            COLOR_WRITE_MASK_WIDTH, POLYGON_MODE_WIDTH, TOPOLOGY_WIDTH, DEPTH_BIAS_ENABLE_WIDTH, FLAT_SHADING_WIDTH,
            ALPHA_TEST_WIDTH};

    /**
     * GL default state with Vulkan enum values (D1's initial bits): blend off (ONE, ZERO, ADD for color and alpha), depth test
     * off, depth write on, compare LESS, culling off (the GL cull face BACK only matters once culling is enabled), CCW, color
     * mask RGBA, fill, no depth bias, smooth shading, no alpha test. The topology bits are 0 (D2 ORs its topology in).
     */
    public static final long DEFAULT =
            ((long) VK10.VK_BLEND_FACTOR_ONE << SRC_COLOR_BLEND_FACTOR_SHIFT)
                    | ((long) VK10.VK_BLEND_FACTOR_ZERO << DST_COLOR_BLEND_FACTOR_SHIFT)
                    | ((long) VK10.VK_BLEND_FACTOR_ONE << SRC_ALPHA_BLEND_FACTOR_SHIFT)
                    | ((long) VK10.VK_BLEND_FACTOR_ZERO << DST_ALPHA_BLEND_FACTOR_SHIFT)
                    | ((long) VK10.VK_BLEND_OP_ADD << COLOR_BLEND_OP_SHIFT)
                    | ((long) VK10.VK_BLEND_OP_ADD << ALPHA_BLEND_OP_SHIFT)
                    | (1L << DEPTH_WRITE_ENABLE_SHIFT)
                    | ((long) VK10.VK_COMPARE_OP_LESS << DEPTH_COMPARE_OP_SHIFT)
                    | ((long) VK10.VK_CULL_MODE_NONE << CULL_MODE_SHIFT)
                    | ((long) VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE << FRONT_FACE_CLOCKWISE_SHIFT)
                    | ((long) (VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT | VK10.VK_COLOR_COMPONENT_B_BIT
                    | VK10.VK_COLOR_COMPONENT_A_BIT) << COLOR_WRITE_MASK_SHIFT)
                    | ((long) VK10.VK_POLYGON_MODE_FILL << POLYGON_MODE_SHIFT);

    /** Returns {@code key} with the field replaced by {@code value} (a Vulkan enum value / flag set / 0-1 for booleans). */
    public static long set(long key, int field, int value) {
        int shift = SHIFTS[field];
        int width = WIDTHS[field];
        assert (value & ~((1 << width) - 1)) == 0 : "value " + value + " does not fit the " + width + "-bit field " + field;
        long mask = mask(shift, width);
        return (key & ~mask) | (((long) value << shift) & mask);
    }

    /** The value of the field in {@code key}. */
    public static int get(long key, int field) {
        return (int) ((key >>> SHIFTS[field]) & ((1L << WIDTHS[field]) - 1));
    }

    /** Bit position of the field's lowest bit. */
    public static int shift(int field) {
        return SHIFTS[field];
    }

    /** Number of bits of the field. */
    public static int width(int field) {
        return WIDTHS[field];
    }

    /** In-place bit mask of the field. */
    public static long mask(int field) {
        return mask(SHIFTS[field], WIDTHS[field]);
    }

    private static long mask(int shift, int width) {
        return ((1L << width) - 1) << shift;
    }
}
