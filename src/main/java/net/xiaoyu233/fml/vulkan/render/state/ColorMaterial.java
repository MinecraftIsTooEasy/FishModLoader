package net.xiaoyu233.fml.vulkan.render.state;

/** glColorMaterial modes (the face argument is ignored). */
public enum ColorMaterial {
    AMBIENT, DIFFUSE, AMBIENT_AND_DIFFUSE;

    /** Decodes the GL constant ({@code Appendix C}); throws {@link IllegalArgumentException} for a value MITE never uses. */
    public static ColorMaterial of(int glEnum) {
        return switch (glEnum) {
            case 4608 -> ColorMaterial.AMBIENT;
            case 4609 -> ColorMaterial.DIFFUSE;
            case 5634 -> ColorMaterial.AMBIENT_AND_DIFFUSE;
            default -> throw new IllegalArgumentException("Unsupported GL constant " + glEnum + " for ColorMaterial");
        };
    }
}
