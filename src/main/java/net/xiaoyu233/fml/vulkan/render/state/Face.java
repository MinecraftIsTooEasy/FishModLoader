package net.xiaoyu233.fml.vulkan.render.state;

/** Culled face (GL_FRONT / GL_BACK; FRONT_AND_BACK is never used by MITE). */
public enum Face {
    FRONT, BACK;

    /** Decodes the GL constant ({@code Appendix C}); throws {@link IllegalArgumentException} for a value MITE never uses. */
    public static Face of(int glEnum) {
        return switch (glEnum) {
            case 1028 -> Face.FRONT;
            case 1029 -> Face.BACK;
            default -> throw new IllegalArgumentException("Unsupported GL constant " + glEnum + " for Face");
        };
    }
}
