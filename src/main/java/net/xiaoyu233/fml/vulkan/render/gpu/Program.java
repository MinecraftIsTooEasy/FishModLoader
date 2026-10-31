package net.xiaoyu233.fml.vulkan.render.gpu;

import net.xiaoyu233.fml.vulkan.render.state.VertexFormat;

/**
 * Shader programs of the native renderer (1.9); the ordinal is bits 0-3 of the material key. Each program names its SPIR-V modules
 * ({@code /assets/fml_vulkan/shaders/native/<name>.vert.spv} and {@code .frag.spv}, built from {@code src/main/shaders/native}) and the
 * vertex format it consumes.
 */
public enum Program {
    TERRAIN("terrain", VertexFormat.TERRAIN),
    STREAM("stream", VertexFormat.UNIVERSAL),
    STATIC("static", VertexFormat.UNIVERSAL),
    LINES("lines", VertexFormat.LINE),
    /** Shaders owned by NR-20 ({@code end_portal.vert/.frag}); pipelines of this program exist only when its modules are in the jar. */
    PORTAL("end_portal", VertexFormat.PORTAL),
    /** Full-screen triangle, no vertex input. */
    BLIT("blit", null);

    private static final Program[] VALUES = values();

    private final String shaderName;
    private final VertexFormat format;

    Program(String shaderName, VertexFormat format) {
        this.shaderName = shaderName;
        this.format = format;
    }

    /** Base name of the SPIR-V modules. */
    public String shaderName() {
        return shaderName;
    }

    /** The vertex format the program reads; {@code null} for {@link #BLIT} (no vertex input). */
    public VertexFormat vertexFormat() {
        return format;
    }

    /** Program of a material key's bits 0-3, or {@code null} for an unused value. */
    public static Program ofOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
    }
}
