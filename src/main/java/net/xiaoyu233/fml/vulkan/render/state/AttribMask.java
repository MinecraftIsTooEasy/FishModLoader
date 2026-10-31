package net.xiaoyu233.fml.vulkan.render.state;

/**
 * Native attribute groups of {@code Draw.pushAttrib/popAttrib} and the decoding of the GL attribute bits MITE sets (Appendix C):
 * 0x0B71 = CURRENT|POLYGON_STIPPLE|PIXEL_MODE|LIGHTING|DEPTH_BUFFER|ACCUM|VIEWPORT decodes to CURRENT, LIGHTING, DEPTH, VIEWPORT and
 * 0x0B74 = LINE|POLYGON_STIPPLE|PIXEL_MODE|LIGHTING|DEPTH_BUFFER|ACCUM|VIEWPORT decodes to LINE, LIGHTING, DEPTH, VIEWPORT.
 */
public final class AttribMask {
    public static final int CURRENT = 1, LIGHTING = 2, DEPTH = 4, VIEWPORT = 8, LINE = 16, ENABLE = 32,
            COLOR_BUFFER = 64, FOG = 128, POLYGON = 256, TEXTURE = 512;

    private AttribMask() {
    }

    /** Maps GL_*_BIT values to native groups; bits without native state (point, stencil, accum, pixel mode, stipple, ...) are ignored. */
    public static int decode(int glAttribMask) {
        int m = 0;
        if ((glAttribMask & 0x1) != 0) m |= CURRENT;           // GL_CURRENT_BIT
        if ((glAttribMask & 0x4) != 0) m |= LINE;              // GL_LINE_BIT
        if ((glAttribMask & 0x8) != 0) m |= POLYGON;           // GL_POLYGON_BIT
        if ((glAttribMask & 0x40) != 0) m |= LIGHTING;         // GL_LIGHTING_BIT
        if ((glAttribMask & 0x80) != 0) m |= FOG;              // GL_FOG_BIT
        if ((glAttribMask & 0x100) != 0) m |= DEPTH;           // GL_DEPTH_BUFFER_BIT
        if ((glAttribMask & 0x800) != 0) m |= VIEWPORT;        // GL_VIEWPORT_BIT
        if ((glAttribMask & 0x2000) != 0) m |= ENABLE;         // GL_ENABLE_BIT
        if ((glAttribMask & 0x4000) != 0) m |= COLOR_BUFFER;   // GL_COLOR_BUFFER_BIT
        if ((glAttribMask & 0x40000) != 0) m |= TEXTURE;       // GL_TEXTURE_BIT
        return m;
    }
}
