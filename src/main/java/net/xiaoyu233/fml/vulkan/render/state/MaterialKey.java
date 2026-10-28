package net.xiaoyu233.fml.vulkan.render.state;

/**
 * Bit layout of the 64-bit material key (1.7):
 * <pre>
 * bits 0-3 program, 4 textured, 5 lightmap, 6 fog, 7 overlay, 8-9 alpha mode, 10-17 alpha ref index, 18 blend, 19-22 src factor,
 * 23-26 dst factor, 27 depth test, 28-30 depth func, 31 depth write, 32 cull back, 33-35 colour mask R,G,B, 36 polygon offset,
 * 37 logic-op cursor, 38 cull front instead of back, 39-63 reserved (0). Culling is encoded as: none = bits 32 and 38 clear, back = bit 32 only,
 * front = bit 38 only ({@link Draw} never sets both)
 * </pre>
 */
public final class MaterialKey {
    /** Bits that become dynamic pipeline state with extended dynamic state: depth test, func, write, cull back, cull front. */
    private static final long DYNAMIC_DEPTH_CULL_BITS = (1L << 27) | (7L << 28) | (1L << 31) | (1L << 32) | (1L << 38);

    private MaterialKey() {
    }

    public static int program(long k) {
        return (int) (k & 0xF);
    }

    public static boolean textured(long k) {
        return (k & (1L << 4)) != 0;
    }

    public static boolean lightmap(long k) {
        return (k & (1L << 5)) != 0;
    }

    public static boolean fog(long k) {
        return (k & (1L << 6)) != 0;
    }

    public static boolean overlay(long k) {
        return (k & (1L << 7)) != 0;
    }

    /** 0 none, 1 fixed GREATER ref, 2 dynamic ref in push constants. */
    public static int alphaMode(long k) {
        return (int) ((k >>> 8) & 3);
    }

    public static int alphaRefIndex(long k) {
        return (int) ((k >>> 10) & 0xFF);
    }

    public static boolean blend(long k) {
        return (k & (1L << 18)) != 0;
    }

    /** {@link BlendFactor} ordinal. */
    public static int srcFactor(long k) {
        return (int) ((k >>> 19) & 0xF);
    }

    /** {@link BlendFactor} ordinal. */
    public static int dstFactor(long k) {
        return (int) ((k >>> 23) & 0xF);
    }

    public static boolean depthTest(long k) {
        return (k & (1L << 27)) != 0;
    }

    /** {@link DepthFunc} ordinal. */
    public static int depthFunc(long k) {
        return (int) ((k >>> 28) & 7);
    }

    public static boolean depthWrite(long k) {
        return (k & (1L << 31)) != 0;
    }

    public static boolean cullBack(long k) {
        return (k & (1L << 32)) != 0;
    }

    /** Colour mask bits: 1 = R, 2 = G, 4 = B (alpha is never written). */
    public static int colorMask(long k) {
        return (int) ((k >>> 33) & 7);
    }

    public static boolean polygonOffset(long k) {
        return (k & (1L << 36)) != 0;
    }

    public static boolean logicOp(long k) {
        return (k & (1L << 37)) != 0;
    }

    public static boolean cullFront(long k) {
        return (k & (1L << 38)) != 0;
    }

    /** The key a pipeline is cached under: with dynamic depth/cull state those bits are masked out, otherwise the key is used as is. */
    public static long pipelineKey(long k, boolean dynamicDepthCull) {
        return dynamicDepthCull ? k & ~DYNAMIC_DEPTH_CULL_BITS : k;
    }

    /** Packs the fields of 1.7 (the layout of the class comment); every argument outside its field width is masked. */
    public static long pack(int program, boolean textured, boolean lightmap, boolean fog, boolean overlay, int alphaMode, int alphaRefIndex,
            boolean blend, int srcFactor, int dstFactor, boolean depthTest, int depthFunc, boolean depthWrite, boolean cullBack,
            int colorMask, boolean polygonOffset, boolean logicOp, boolean cullFront) {
        long k = program & 0xFL;
        if (textured) k |= 1L << 4;
        if (lightmap) k |= 1L << 5;
        if (fog) k |= 1L << 6;
        if (overlay) k |= 1L << 7;
        k |= (alphaMode & 3L) << 8;
        k |= (alphaRefIndex & 0xFFL) << 10;
        if (blend) k |= 1L << 18;
        k |= (srcFactor & 0xFL) << 19;
        k |= (dstFactor & 0xFL) << 23;
        if (depthTest) k |= 1L << 27;
        k |= (depthFunc & 7L) << 28;
        if (depthWrite) k |= 1L << 31;
        if (cullBack) k |= 1L << 32;
        k |= (colorMask & 7L) << 33;
        if (polygonOffset) k |= 1L << 36;
        if (logicOp) k |= 1L << 37;
        if (cullFront) k |= 1L << 38;
        return k;
    }
}
