package org.lwjgl.input;

import net.xiaoyu233.fml.vulkan.window.InputManager;
import net.xiaoyu233.fml.vulkan.window.WindowManager;
import org.lwjgl.LWJGLException;

import java.util.HashMap;
import java.util.Map;

/**
 * LWJGL 2 API: the mouse (public API and event semantics of LWJGL 2.9.0). Owned by module E. The state and the queue live
 * in {@link InputManager}, fed by the GLFW callbacks: positions are framebuffer pixels with the origin at the bottom-left,
 * move and wheel events have button -1, the wheel comes in multiples of 120, {@link #getDX()}/{@link #getDY()}/
 * {@link #getDWheel()} accumulate since the previous call and {@link #setGrabbed(boolean)} uses the disabled (raw) GLFW cursor.
 * {@code Display.create()} creates the mouse.
 */
public class Mouse {
    public static final int EVENT_SIZE = 22;

    private static final int BUTTON_NAMES = 16;
    private static final String[] buttonName = new String[BUTTON_NAMES];
    private static final Map<String, Integer> buttonMap = new HashMap<>(BUTTON_NAMES);

    static {
        for (int i = 0; i < BUTTON_NAMES; i++) {
            buttonName[i] = "BUTTON" + i;
            buttonMap.put(buttonName[i], i);
        }
    }

    private static Cursor currentCursor;

    private Mouse() {
    }

    public static Cursor getNativeCursor() {
        return currentCursor;
    }

    /** Sets the cursor shown while the mouse is not grabbed (null: the default arrow); returns the previous one. */
    public static Cursor setNativeCursor(Cursor cursor) throws LWJGLException {
        Cursor old = currentCursor;
        currentCursor = cursor;
        WindowManager.setNativeCursor(cursor == null ? 0L : cursor.getHandle());
        return old;
    }

    public static boolean isClipMouseCoordinatesToWindow() {
        return InputManager.isClipMouseCoordinatesToWindow();
    }

    public static void setClipMouseCoordinatesToWindow(boolean clip) {
        InputManager.setClipMouseCoordinatesToWindow(clip);
    }

    /** Moves the cursor (framebuffer pixels, origin bottom-left); while grabbed it takes effect when the cursor is released. */
    public static void setCursorPosition(int newX, int newY) {
        InputManager.setCursorPosition(newX, newY);
    }

    /** Creates the mouse (Display.create() does it already). Mouse and keyboard are independent of each other. */
    public static void create() throws LWJGLException {
        InputManager.createMouse();
    }

    public static boolean isCreated() {
        return InputManager.isMouseCreated();
    }

    public static void destroy() {
        InputManager.destroyMouse();
    }

    /** Nothing to poll: the GLFW callbacks keep the state current. */
    public static void poll() {
    }

    public static boolean isButtonDown(int button) {
        return InputManager.isMouseButtonDown(button);
    }

    public static String getButtonName(int button) {
        return button >= 0 && button < BUTTON_NAMES ? buttonName[button] : null;
    }

    public static int getButtonIndex(String buttonName) {
        Integer ret = buttonMap.get(buttonName);
        return ret == null ? -1 : ret;
    }

    /** Moves to the next event; false when the queue is empty. */
    public static boolean next() {
        return InputManager.nextMouseEvent();
    }

    /** The button of the event, -1 for a move or wheel event. */
    public static int getEventButton() {
        return InputManager.getMouseEventButton();
    }

    public static boolean getEventButtonState() {
        return InputManager.getMouseEventButtonState();
    }

    public static int getEventDX() {
        return InputManager.getMouseEventDX();
    }

    public static int getEventDY() {
        return InputManager.getMouseEventDY();
    }

    public static int getEventX() {
        return InputManager.getMouseEventX();
    }

    public static int getEventY() {
        return InputManager.getMouseEventY();
    }

    public static int getEventDWheel() {
        return InputManager.getMouseEventDWheel();
    }

    public static long getEventNanoseconds() {
        return InputManager.getMouseEventNanoseconds();
    }

    public static int getX() {
        return InputManager.getMouseX();
    }

    public static int getY() {
        return InputManager.getMouseY();
    }

    public static int getDX() {
        return InputManager.getMouseDX();
    }

    public static int getDY() {
        return InputManager.getMouseDY();
    }

    public static int getDWheel() {
        return InputManager.getMouseDWheel();
    }

    public static int getButtonCount() {
        return 8;
    }

    public static boolean hasWheel() {
        return true;
    }

    public static boolean isGrabbed() {
        return InputManager.isMouseGrabbed();
    }

    public static void setGrabbed(boolean grab) {
        InputManager.setMouseGrabbed(grab);
    }

    /** Cursor animation is not emulated, nothing to update. */
    public static void updateCursor() {
    }

    public static boolean isInsideWindow() {
        return InputManager.isMouseInsideWindow();
    }
}
