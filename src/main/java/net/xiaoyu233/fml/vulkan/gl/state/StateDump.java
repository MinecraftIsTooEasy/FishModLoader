package net.xiaoyu233.fml.vulkan.gl.state;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.render.legacy.StateMirror;
import net.xiaoyu233.fml.vulkan.render.state.Draw;

/**
 * Debug: one log line with the render state at the start of the world pass, from both sides (native {@code Draw} and the GL
 * emulation {@code GLState}), plus the stack depths. Written every 5 s and whenever the open GUI screen changes, so a state that
 * leaks out of a GUI shows up as the first line after the screen closed. On with {@code -Dfml.vulkan.stateDump=true}. Render thread.
 */
public final class StateDump {
    private static final boolean ENABLED = Boolean.getBoolean("fml.vulkan.stateDump");
    private static final long PERIOD_NANOS = 5_000_000_000L;
    private static long last;
    private static Object lastScreen = StateDump.class;

    private StateDump() {
    }

    public static void worldPassStart(Object screen) {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        boolean screenChanged = screen != lastScreen;
        if (!screenChanged && now - last < PERIOD_NANOS) {
            return;
        }
        last = now;
        String reason = screenChanged ? "screen " + (lastScreen == StateDump.class ? "-" : name(lastScreen)) + " -> " + name(screen) : "periodic";
        lastScreen = screen;
        GLState g = GLState.get();
        VkLog.LOGGER.info("[stateDump] {} | Draw: depthTest {} depthMask {} depthFunc {} cull {} blend {} alphaTest {} colorMask {} fog {} {} {}..{} "
                        + "dens {} col {},{},{} lighting {} unit {} lightmap {} tex0 {} poseDepth {} projDepth {} attribDepth {} | GL: caps 0x{} "
                        + "depthMask {} matrixMode {} unit {} attribDepth {} | mirror nativeDirty 0x{} legacyDirty 0x{}",
                reason, Draw.depthTestEnabled(), Draw.depthMaskEnabled(), Draw.depthFuncState(), Draw.cullEnabled(), Draw.blendEnabled(),
                Draw.alphaTestEnabled(), Draw.colorMaskBits(), Draw.fogEnabled(), Draw.fogModeState(), Draw.fogStartValue(), Draw.fogEndValue(),
                Draw.fogDensityValue(), Draw.fogColorComponent(0), Draw.fogColorComponent(1), Draw.fogColorComponent(2), Draw.lightingEnabled(),
                Draw.activeUnit(), Draw.lightmapEnabled(), Draw.boundTexture(0), Draw.pose().depth(), Draw.projection().depth(), Draw.attribDepth(),
                Long.toHexString(g.caps), g.depthMask, g.matrixMode, g.activeTextureUnit, g.attribStack.depth(),
                Integer.toHexString(StateMirror.nativeDirty), Integer.toHexString(StateMirror.legacyDirty));
    }

    private static String name(Object screen) {
        return screen == null ? "none" : screen.getClass().getSimpleName();
    }
}
