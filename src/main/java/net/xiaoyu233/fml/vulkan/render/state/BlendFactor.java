package net.xiaoyu233.fml.vulkan.render.state;

/** Blend factors MITE uses; the ordinals are the material key encoding (1.7). */
public enum BlendFactor {
    ZERO, ONE, SRC_COLOR, ONE_MINUS_SRC_COLOR, SRC_ALPHA, ONE_MINUS_SRC_ALPHA, DST_ALPHA, ONE_MINUS_DST_ALPHA, DST_COLOR, ONE_MINUS_DST_COLOR;

    /** Decodes the GL constant ({@code Appendix C}); throws {@link IllegalArgumentException} for a value MITE never uses. */
    public static BlendFactor of(int glEnum) {
        return switch (glEnum) {
            case 0 -> BlendFactor.ZERO;
            case 1 -> BlendFactor.ONE;
            case 768 -> BlendFactor.SRC_COLOR;
            case 769 -> BlendFactor.ONE_MINUS_SRC_COLOR;
            case 770 -> BlendFactor.SRC_ALPHA;
            case 771 -> BlendFactor.ONE_MINUS_SRC_ALPHA;
            case 772 -> BlendFactor.DST_ALPHA;
            case 773 -> BlendFactor.ONE_MINUS_DST_ALPHA;
            case 774 -> BlendFactor.DST_COLOR;
            case 775 -> BlendFactor.ONE_MINUS_DST_COLOR;
            default -> throw new IllegalArgumentException("Unsupported GL constant " + glEnum + " for BlendFactor");
        };
    }
}
