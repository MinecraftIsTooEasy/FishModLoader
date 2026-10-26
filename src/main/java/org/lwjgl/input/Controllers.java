package org.lwjgl.input;

import org.lwjgl.LWJGLException;

/**
 * LWJGL 2 API: the game controllers (public API of LWJGL 2.9.0). Owned by module E. There are never any controllers in
 * the Vulkan port (like LWJGL 2 without JInput): {@code create()} succeeds, the count is 0 and there are no events.
 */
public class Controllers {
    private static boolean created;

    public Controllers() {
    }

    public static void create() throws LWJGLException {
        created = true;
    }

    public static boolean isCreated() {
        return created;
    }

    public static void destroy() {
        created = false;
    }

    public static Controller getController(int index) {
        throw new IndexOutOfBoundsException("Index: " + index + ", Size: 0");
    }

    public static int getControllerCount() {
        return 0;
    }

    public static void poll() {
    }

    public static void clearEvents() {
    }

    public static boolean next() {
        return false;
    }

    public static Controller getEventSource() {
        return null;
    }

    public static int getEventControlIndex() {
        return 0;
    }

    public static boolean isEventButton() {
        return false;
    }

    public static boolean isEventAxis() {
        return false;
    }

    public static boolean isEventXAxis() {
        return false;
    }

    public static boolean isEventYAxis() {
        return false;
    }

    public static boolean isEventPovX() {
        return false;
    }

    public static boolean isEventPovY() {
        return false;
    }

    public static long getEventNanoseconds() {
        return 0L;
    }
}
