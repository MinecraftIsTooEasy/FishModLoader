package net.xiaoyu233.fml.vulkan;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backend options. Every value is read from a system property on each call (module G pre-populates the properties from
 * {@code config/fml-vulkan.properties} for keys that are not set already), so callers that need a value per frame should
 * cache it. A blank or unparsable property counts as "not set" (unparsable values are logged once).
 */
public final class VulkanConfig {
    public static final String PROP_VSYNC = "fml.vulkan.vsync";
    public static final String PROP_PRESENT_MODE = "fml.vulkan.presentMode";
    public static final String PROP_FRAMES_IN_FLIGHT = "fml.vulkan.framesInFlight";
    public static final String PROP_RETINA = "fml.vulkan.retina";
    public static final String PROP_VALIDATION = "fml.vulkan.validation";
    public static final String PROP_DEBUG = "fml.vulkan.debug";
    public static final String PROP_CAPTURE = "fml.vulkan.capture";
    public static final String PROP_CAPTURE_DIR = "fml.vulkan.captureDir";
    public static final String PROP_EXIT_AFTER_FRAMES = "fml.vulkan.exitAfterFrames";
    public static final String PROP_RAW_MOUSE = "fml.vulkan.rawMouse";
    public static final String PROP_BORDERLESS = "fml.vulkan.borderless";
    public static final String PROP_NO_DRAW_MERGE = "fml.vulkan.noDrawMerge";
    public static final String PROP_GAME_DIR = "fml.gameDir";

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private VulkanConfig() {
    }

    /**
     * fml.vulkan.vsync. The effective vsync normally follows {@code Display.setVSyncEnabled}; this value is only the initial
     * state (true when unset) and the override when {@link #vsyncForced()} is true.
     */
    public static boolean vsync() {
        return bool(PROP_VSYNC, true);
    }

    /** True when fml.vulkan.vsync is set explicitly, i.e. it overrides {@code Display.setVSyncEnabled}. */
    public static boolean vsyncForced() {
        return raw(PROP_VSYNC) != null;
    }

    /** fml.vulkan.presentMode: one of auto|fifo|mailbox|immediate (lower case, default auto: vsync -> FIFO, else MAILBOX -> IMMEDIATE -> FIFO). */
    public static String presentMode() {
        String v = raw(PROP_PRESENT_MODE);
        if (v == null) return "auto";
        String mode = v.toLowerCase(Locale.ROOT);
        switch (mode) {
            case "auto", "fifo", "mailbox", "immediate" -> {
                return mode;
            }
            default -> {
                warnInvalid(PROP_PRESENT_MODE, v, "auto");
                return "auto";
            }
        }
    }

    /** fml.vulkan.framesInFlight: default 2, clamped to 1..3. */
    public static int framesInFlight() {
        return Math.max(1, Math.min(3, integer(PROP_FRAMES_IN_FLIGHT, 2)));
    }

    /** fml.vulkan.retina: macOS full-resolution framebuffer, default true. */
    public static boolean retina() {
        return bool(PROP_RETINA, true);
    }

    /** fml.vulkan.validation: enable VK_LAYER_KHRONOS_validation when present, default false. */
    public static boolean validation() {
        return bool(PROP_VALIDATION, false);
    }

    /** fml.vulkan.debug: extra logging and debug utils labels, default false. */
    public static boolean debug() {
        return bool(PROP_DEBUG, false);
    }

    /** fml.vulkan.capture, e.g. "60,300": presented-frame numbers whose offscreen color is written as PNG (sorted, distinct; empty when unset). */
    public static int[] captureFrames() {
        String v = raw(PROP_CAPTURE);
        if (v == null) return new int[0];
        int[] frames = new int[0];
        for (String token : v.split("[,;\\s]+")) {
            if (token.isEmpty()) continue;
            try {
                int frame = Integer.parseInt(token);
                if (frame < 0) throw new NumberFormatException(token);
                frames = Arrays.copyOf(frames, frames.length + 1);
                frames[frames.length - 1] = frame;
            } catch (NumberFormatException e) {
                warnInvalid(PROP_CAPTURE, token, "(ignored)");
            }
        }
        return Arrays.stream(frames).sorted().distinct().toArray();
    }

    /** fml.vulkan.captureDir, default {@code <gameDir>/vk-captures}; a relative value is resolved against {@link #gameDir()}. */
    public static Path captureDir() {
        String v = raw(PROP_CAPTURE_DIR);
        Path gameDir = gameDir();
        return v == null ? gameDir.resolve("vk-captures") : gameDir.resolve(v).normalize();
    }

    /** fml.vulkan.exitAfterFrames: the test harness quits the game after this many presented frames; 0 (default) = never. */
    public static long exitAfterFrames() {
        return Math.max(0L, longValue(PROP_EXIT_AFTER_FRAMES, 0L));
    }

    /** fml.vulkan.rawMouse: use GLFW raw mouse motion while the cursor is grabbed, default true. */
    public static boolean rawMouse() {
        return bool(PROP_RAW_MOUSE, true);
    }

    /**
     * fml.vulkan.borderless: fullscreen as a borderless window covering the monitor (otherwise exclusive), default true.
     * <b>macOS:</b> a plain borderless window cannot cover the menu bar and the Dock, so there the default is the window attached to
     * the monitor (GLFW "exclusive" path; hides both, keeps the desktop resolution when the requested size equals it) and the
     * borderless window is only used when this property is set explicitly (module E tests {@code System.getProperty} for presence,
     * so nothing may pre-populate it with the default value).
     */
    public static boolean borderlessFullscreen() {
        return bool(PROP_BORDERLESS, true);
    }

    /** fml.vulkan.noDrawMerge: record every draw immediately instead of merging contiguous draws (debugging aid), default false. */
    public static boolean noDrawMerge() {
        return bool(PROP_NO_DRAW_MERGE, false);
    }

    /** fml.gameDir (set by module G), falling back to user.dir. Absolute and normalized. */
    public static Path gameDir() {
        String v = raw(PROP_GAME_DIR);
        if (v == null) v = System.getProperty("user.dir", ".");
        return Paths.get(v).toAbsolutePath().normalize();
    }

    private static String raw(String key) {
        String v = System.getProperty(key);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private static boolean bool(String key, boolean def) {
        String v = raw(key);
        if (v == null) return def;
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> {
                warnInvalid(key, v, String.valueOf(def));
                yield def;
            }
        };
    }

    private static int integer(String key, int def) {
        String v = raw(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            warnInvalid(key, v, String.valueOf(def));
            return def;
        }
    }

    private static long longValue(String key, long def) {
        String v = raw(key);
        if (v == null) return def;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            warnInvalid(key, v, String.valueOf(def));
            return def;
        }
    }

    private static void warnInvalid(String key, String value, String used) {
        if (WARNED.add(key + '=' + value)) {
            VkLog.LOGGER.warn("Invalid value '{}' for system property {}, using {}", value, key, used);
        }
    }
}
