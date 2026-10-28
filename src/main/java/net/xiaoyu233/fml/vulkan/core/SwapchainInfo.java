package net.xiaoyu233.fml.vulkan.core;

import java.lang.reflect.Field;
import org.lwjgl.vulkan.KHRSurface;

/**
 * Read-only view of the live swapchain for the F3 overlay (a separate class so the overlay needs no edit of {@link RenderCore}).
 * The swapchain reference is read reflectively once per call (a few times per second at most); every accessor is null safe.
 */
public final class SwapchainInfo {
    private static Field field;
    private static boolean lookedUp;

    private SwapchainInfo() {
    }

    private static Swapchain current() {
        if (!lookedUp) {
            lookedUp = true;
            try {
                Field f = RenderCore.class.getDeclaredField("swapchain");
                f.setAccessible(true);
                field = f;
            } catch (ReflectiveOperationException | RuntimeException e) {
                field = null;
            }
        }
        try {
            return field == null ? null : (Swapchain) field.get(null);
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    /** Present mode in use ("IMMEDIATE", "MAILBOX", "FIFO", "FIFO_RELAXED"), "-" while there is no swapchain. */
    public static String presentMode() {
        Swapchain sc = current();
        if (sc == null) {
            return "-";
        }
        return switch (sc.presentMode) {
            case KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE";
            case KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX";
            case KHRSurface.VK_PRESENT_MODE_FIFO_KHR -> "FIFO";
            case KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO_RELAXED";
            default -> "mode " + sc.presentMode;
        };
    }

    /** True when the present mode waits for the display refresh (FIFO, FIFO_RELAXED). */
    public static boolean vsyncMode() {
        Swapchain sc = current();
        return sc != null && (sc.presentMode == KHRSurface.VK_PRESENT_MODE_FIFO_KHR || sc.presentMode == KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR);
    }

    /** Swapchain extent width in pixels, 0 without a swapchain. */
    public static int width() {
        Swapchain sc = current();
        return sc == null ? 0 : sc.width;
    }

    /** Swapchain extent height in pixels, 0 without a swapchain. */
    public static int height() {
        Swapchain sc = current();
        return sc == null ? 0 : sc.height;
    }
}
