package net.xiaoyu233.fml.vulkan.render.frame;

import net.minecraft.Minecraft;
import net.xiaoyu233.fml.FishModLoader;
import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.mixin.opt.OptMixinPlugin;
import net.xiaoyu233.fml.vulkan.window.InputManager;

/**
 * WP-27 (OPT): AFK frame cap. While the player is in a world with no screen open and has produced no input for
 * {@code fml.vulkan.afkSeconds} (default 120), the frame limit is lowered to {@code fml.vulkan.afkFps} (default 30). Any key,
 * mouse button or cursor movement lifts the cap on the next limiter call (one frame later at most). The cap never raises a limit
 * the player set, and it is off when {@code fml.vulkan.afkSeconds=0} or {@code fml.vulkan.afkFps<=0}, when the mithril mod is
 * loaded, or when the fps area is off ({@code -Dfml.vulkan.opt.fps=false} or {@code -Dfml.vulkan.opt=false}).
 *
 * <p>Input activity is sampled by {@link #clampFps(int)} once per limiter call on the render thread, from the state that
 * survives the game's own consumption: keys and mouse buttons currently held, the cursor position, and any key events still
 * queued. Key or button presses that start and end between two samples are not seen (at the AFK cap a sample is ~33 ms apart).
 * Consuming the queues here would steal events from the game, so they are not read. A monotonic activity counter in
 * InputManager would remove that gap (InputManager is owned by R3, so it is not edited here).
 *
 * <p>Integration: {@link FramePacer#limit(int)} calls {@link #clampFps(int)} on its argument before {@code effectiveFps}.
 */
public final class FpsPolicy {
    /** Default cap while AFK, in frames per second. */
    public static final int DEFAULT_AFK_FPS = 30;
    /** Default idle time before the cap applies, in seconds. */
    public static final int DEFAULT_AFK_SECONDS = 120;

    private static boolean configRead;
    private static boolean enabled;
    private static int afkFps;
    private static long afkNanos;

    private static volatile long lastActivityNanos = System.nanoTime();
    private static boolean seenCursor;
    private static int lastCursorX;
    private static int lastCursorY;
    private static boolean capActive;

    private FpsPolicy() {
    }

    private static void readConfig() {
        int fps = intProperty("fml.vulkan.afkFps", DEFAULT_AFK_FPS);
        int seconds = intProperty("fml.vulkan.afkSeconds", DEFAULT_AFK_SECONDS);
        boolean mithril = FishModLoader.hasMod("mithril");
        boolean areaOn = OptMixinPlugin.areaActive("fps");
        afkFps = fps;
        afkNanos = seconds * 1_000_000_000L;
        enabled = areaOn && !mithril && fps > 0 && seconds > 0;
        if (mithril) {
            VkLog.LOGGER.info("AFK frame cap disabled: mod 'mithril' is loaded");
        }
        configRead = true;
    }

    private static int intProperty(String key, int def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            VkLog.LOGGER.warn("Ignoring non-integer -D{}={}", key, v);
            return def;
        }
    }

    /** True when the AFK cap can apply at all (config read; false for mithril, fml.vulkan.opt.fps=false, or a zero setting). */
    public static boolean enabled() {
        if (!configRead) {
            readConfig();
        }
        return enabled;
    }

    /**
     * The AFK cap for a player whose last input was at {@code lastInputNanos} ({@code System.nanoTime()} base): {@code afkFps} when
     * in a world with no screen open and idle for at least {@code afkSeconds}, otherwise {@code 0} (no cap).
     */
    public static int afkCapFps(long lastInputNanos) {
        if (!enabled()) {
            return 0;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.currentScreen != null) {
            return 0;
        }
        return System.nanoTime() - lastInputNanos >= afkNanos ? afkFps : 0;
    }

    /**
     * Samples the input state, updates the last-activity time and returns the limit to use for {@code fps}: the AFK cap (when it
     * applies) lowered to, never raised, the requested value; {@code fps <= 0} (unlimited) becomes the cap. Render thread only.
     */
    public static int clampFps(int fps) {
        if (!enabled()) {
            return fps;
        }
        sampleInput();
        int cap = afkCapFps(lastActivityNanos);
        boolean now = cap > 0;
        if (now != capActive) {
            capActive = now;
            VkLog.LOGGER.debug("AFK frame cap {}", now ? "engaged at " + cap + " fps" : "released");
        }
        if (cap <= 0) {
            return fps;
        }
        return fps <= 0 ? cap : Math.min(fps, cap);
    }

    /** Refreshes {@link #lastActivityNanos} from the non-consuming input state. */
    private static void sampleInput() {
        boolean active = InputManager.getNumKeyboardEvents() > 0;
        for (int key = 0; !active && key < 256; key++) {
            if (InputManager.isKeyDown(key)) {
                active = true;
            }
        }
        for (int button = 0; !active && button < 8; button++) {
            if (InputManager.isMouseButtonDown(button)) {
                active = true;
            }
        }
        int x = InputManager.getMouseX();
        int y = InputManager.getMouseY();
        if (!active && seenCursor && (x != lastCursorX || y != lastCursorY)) {
            active = true;
        }
        seenCursor = true;
        lastCursorX = x;
        lastCursorY = y;
        if (active) {
            lastActivityNanos = System.nanoTime();
        }
    }

    /** The time of the last sampled input activity ({@code System.nanoTime()} base). */
    public static long lastActivityNanos() {
        return lastActivityNanos;
    }
}
