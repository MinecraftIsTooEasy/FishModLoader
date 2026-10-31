package org.lwjgl;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.window.WindowManager;

import java.io.IOException;
import java.util.Locale;

/**
 * LWJGL 2 API: system utilities (public API of LWJGL 2.9.0). Owned by module E. {@code getTime()} counts milliseconds of
 * the monotonic clock, links open through the system (no AWT) and the clipboard is GLFW's.
 */
public final class Sys {
    private static final String VERSION = "2.9.4 (LWJGL 3.4.3 / FML Vulkan)";

    private Sys() {
    }

    public static String getVersion() {
        return VERSION;
    }

    public static void initialize() {
    }

    public static boolean is64Bit() {
        String bits = System.getProperty("sun.arch.data.model");
        if (bits != null) {
            return "64".equals(bits);
        }
        return System.getProperty("os.arch", "").contains("64");
    }

    /** Ticks per second of {@link #getTime()}: 1000, a tick is a millisecond. */
    public static long getTimerResolution() {
        return 1000L;
    }

    /** Milliseconds of the monotonic clock (not affected by changes of the wall clock). */
    public static long getTime() {
        return System.nanoTime() / 1_000_000L;
    }

    /** No native message box: the message goes to the log and to stderr. */
    public static void alert(String title, String message) {
        VkLog.LOGGER.error("{}: {}", title, message);
        System.err.println(title + ": " + message);
    }

    /** Opens a URL with the default handler of the system ({@code open}, {@code rundll32 url.dll,FileProtocolHandler}, {@code xdg-open}). */
    public static boolean openURL(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] command;
        if (os.contains("mac")) {
            command = new String[]{"open", url};
        } else if (os.contains("win")) {
            command = new String[]{"rundll32", "url.dll,FileProtocolHandler", url};
        } else {
            command = new String[]{"xdg-open", url};
        }
        try {
            new ProcessBuilder(command).inheritIO().start();
            return true;
        } catch (IOException e) {
            VkLog.LOGGER.error("Failed to open URL: {}", url, e);
            return false;
        }
    }

    /** The text of the clipboard ("" when empty or not text). */
    public static String getClipboard() {
        return WindowManager.getClipboard();
    }
}
