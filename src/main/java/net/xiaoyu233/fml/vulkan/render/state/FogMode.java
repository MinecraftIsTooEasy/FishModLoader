package net.xiaoyu233.fml.vulkan.render.state;

/** Fog modes (GL_LINEAR 9729, GL_EXP 2048, GL_EXP2 2049). */
public enum FogMode {
    LINEAR, EXP, EXP2;

    /** Decodes the GL constant ({@code Appendix C}); throws {@link IllegalArgumentException} for a value MITE never uses. */
    public static FogMode of(int glEnum) {
        return switch (glEnum) {
            case 9729 -> FogMode.LINEAR;
            case 2048 -> FogMode.EXP;
            case 2049 -> FogMode.EXP2;
            default -> throw new IllegalArgumentException("Unsupported GL constant " + glEnum + " for FogMode");
        };
    }
}
