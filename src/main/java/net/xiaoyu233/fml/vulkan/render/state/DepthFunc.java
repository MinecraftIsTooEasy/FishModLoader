package net.xiaoyu233.fml.vulkan.render.state;

/** Depth comparison functions; the ordinals are the material key encoding (1.7). */
public enum DepthFunc {
    NEVER, LESS, EQUAL, LEQUAL, GREATER, NOTEQUAL, GEQUAL, ALWAYS;

    /** Decodes the GL constant ({@code Appendix C}); throws {@link IllegalArgumentException} for a value MITE never uses. */
    public static DepthFunc of(int glEnum) {
        return switch (glEnum) {
            case 512 -> DepthFunc.NEVER;
            case 513 -> DepthFunc.LESS;
            case 514 -> DepthFunc.EQUAL;
            case 515 -> DepthFunc.LEQUAL;
            case 516 -> DepthFunc.GREATER;
            case 517 -> DepthFunc.NOTEQUAL;
            case 518 -> DepthFunc.GEQUAL;
            case 519 -> DepthFunc.ALWAYS;
            default -> throw new IllegalArgumentException("Unsupported GL constant " + glEnum + " for DepthFunc");
        };
    }
}
