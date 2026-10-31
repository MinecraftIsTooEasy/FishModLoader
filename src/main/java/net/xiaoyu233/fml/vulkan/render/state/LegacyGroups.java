package net.xiaoyu233.fml.vulkan.render.state;

/**
 * Groups of typed {@link Draw} state that the GL safety net mirrors (1.16.3). {@code Draw} setters report the group they changed to
 * {@code StateMirror.onNativeChanged(group)} and accumulate them in {@link Draw#dirtyLegacyGroups()}; the emulation pulls the groups it
 * needs in {@code StateMirror.syncNativeToLegacy()}. The numeric values are only meaningful as bit masks.
 */
public final class LegacyGroups {
    /** Unit 0 / unit 1 texture bindings (including the lightmap texture). */
    public static final int TEXTURE_BIND = 1;
    /** GL_TEXTURE_2D enable per unit (unit 1 = lightmap enable). */
    public static final int TEXTURE_ENABLE = 1 << 1;
    /** Active texture unit. */
    public static final int UNIT = 1 << 2;
    /** Current colour, normal and lightmap coordinate. */
    public static final int CURRENT = 1 << 3;
    /** Blend enable and factors. */
    public static final int BLEND = 1 << 4;
    /** Alpha test enable and reference. */
    public static final int ALPHA = 1 << 5;
    /** Depth test, function and mask. */
    public static final int DEPTH = 1 << 6;
    /** Back-face culling enable and face. */
    public static final int CULL = 1 << 7;
    /** Colour write mask. */
    public static final int COLOR_MASK = 1 << 8;
    /** Polygon offset enable, factor and units. */
    public static final int POLYGON_OFFSET = 1 << 9;
    /** Fog enable, mode, parameters and colour. */
    public static final int FOG = 1 << 10;
    /** Lighting enable, lights 0/1 (enable and eye direction), colour material. */
    public static final int LIGHTING = 1 << 11;
    /** GL_NORMALIZE / GL_RESCALE_NORMAL. */
    public static final int NORMAL_MODE = 1 << 12;
    /** Line width. */
    public static final int LINE = 1 << 13;
    /** Shade model. */
    public static final int SHADE = 1 << 14;
    /** The text cursor logic op. */
    public static final int LOGIC = 1 << 15;
    public static final int ALL = 0xFFFF;

    private LegacyGroups() {
    }
}
