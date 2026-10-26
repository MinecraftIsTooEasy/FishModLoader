package net.xiaoyu233.fml.vulkan.window;

import org.lwjgl.input.Keyboard;

import static org.lwjgl.glfw.GLFW.*;

/**
 * GLFW key token to LWJGL 2 (DirectInput style) key code table. Keys without an LWJGL 2 equivalent map to 0
 * ({@link Keyboard#KEY_NONE}); {@link InputManager} drops events of such keys.
 */
public final class GLFWKeyMap {
    private static final int[] GLFW_TO_LWJGL = new int[GLFW_KEY_LAST + 1];

    static {
        map(GLFW_KEY_ESCAPE, Keyboard.KEY_ESCAPE);
        map(GLFW_KEY_1, Keyboard.KEY_1);
        map(GLFW_KEY_2, Keyboard.KEY_2);
        map(GLFW_KEY_3, Keyboard.KEY_3);
        map(GLFW_KEY_4, Keyboard.KEY_4);
        map(GLFW_KEY_5, Keyboard.KEY_5);
        map(GLFW_KEY_6, Keyboard.KEY_6);
        map(GLFW_KEY_7, Keyboard.KEY_7);
        map(GLFW_KEY_8, Keyboard.KEY_8);
        map(GLFW_KEY_9, Keyboard.KEY_9);
        map(GLFW_KEY_0, Keyboard.KEY_0);
        map(GLFW_KEY_MINUS, Keyboard.KEY_MINUS);
        map(GLFW_KEY_EQUAL, Keyboard.KEY_EQUALS);
        map(GLFW_KEY_BACKSPACE, Keyboard.KEY_BACK);
        map(GLFW_KEY_TAB, Keyboard.KEY_TAB);
        map(GLFW_KEY_Q, Keyboard.KEY_Q);
        map(GLFW_KEY_W, Keyboard.KEY_W);
        map(GLFW_KEY_E, Keyboard.KEY_E);
        map(GLFW_KEY_R, Keyboard.KEY_R);
        map(GLFW_KEY_T, Keyboard.KEY_T);
        map(GLFW_KEY_Y, Keyboard.KEY_Y);
        map(GLFW_KEY_U, Keyboard.KEY_U);
        map(GLFW_KEY_I, Keyboard.KEY_I);
        map(GLFW_KEY_O, Keyboard.KEY_O);
        map(GLFW_KEY_P, Keyboard.KEY_P);
        map(GLFW_KEY_LEFT_BRACKET, Keyboard.KEY_LBRACKET);
        map(GLFW_KEY_RIGHT_BRACKET, Keyboard.KEY_RBRACKET);
        map(GLFW_KEY_ENTER, Keyboard.KEY_RETURN);
        map(GLFW_KEY_LEFT_CONTROL, Keyboard.KEY_LCONTROL);
        map(GLFW_KEY_A, Keyboard.KEY_A);
        map(GLFW_KEY_S, Keyboard.KEY_S);
        map(GLFW_KEY_D, Keyboard.KEY_D);
        map(GLFW_KEY_F, Keyboard.KEY_F);
        map(GLFW_KEY_G, Keyboard.KEY_G);
        map(GLFW_KEY_H, Keyboard.KEY_H);
        map(GLFW_KEY_J, Keyboard.KEY_J);
        map(GLFW_KEY_K, Keyboard.KEY_K);
        map(GLFW_KEY_L, Keyboard.KEY_L);
        map(GLFW_KEY_SEMICOLON, Keyboard.KEY_SEMICOLON);
        map(GLFW_KEY_APOSTROPHE, Keyboard.KEY_APOSTROPHE);
        map(GLFW_KEY_GRAVE_ACCENT, Keyboard.KEY_GRAVE);
        map(GLFW_KEY_LEFT_SHIFT, Keyboard.KEY_LSHIFT);
        map(GLFW_KEY_BACKSLASH, Keyboard.KEY_BACKSLASH);
        map(GLFW_KEY_Z, Keyboard.KEY_Z);
        map(GLFW_KEY_X, Keyboard.KEY_X);
        map(GLFW_KEY_C, Keyboard.KEY_C);
        map(GLFW_KEY_V, Keyboard.KEY_V);
        map(GLFW_KEY_B, Keyboard.KEY_B);
        map(GLFW_KEY_N, Keyboard.KEY_N);
        map(GLFW_KEY_M, Keyboard.KEY_M);
        map(GLFW_KEY_COMMA, Keyboard.KEY_COMMA);
        map(GLFW_KEY_PERIOD, Keyboard.KEY_PERIOD);
        map(GLFW_KEY_SLASH, Keyboard.KEY_SLASH);
        map(GLFW_KEY_RIGHT_SHIFT, Keyboard.KEY_RSHIFT);
        map(GLFW_KEY_KP_MULTIPLY, Keyboard.KEY_MULTIPLY);
        map(GLFW_KEY_LEFT_ALT, Keyboard.KEY_LMENU);
        map(GLFW_KEY_SPACE, Keyboard.KEY_SPACE);
        map(GLFW_KEY_CAPS_LOCK, Keyboard.KEY_CAPITAL);
        map(GLFW_KEY_F1, Keyboard.KEY_F1);
        map(GLFW_KEY_F2, Keyboard.KEY_F2);
        map(GLFW_KEY_F3, Keyboard.KEY_F3);
        map(GLFW_KEY_F4, Keyboard.KEY_F4);
        map(GLFW_KEY_F5, Keyboard.KEY_F5);
        map(GLFW_KEY_F6, Keyboard.KEY_F6);
        map(GLFW_KEY_F7, Keyboard.KEY_F7);
        map(GLFW_KEY_F8, Keyboard.KEY_F8);
        map(GLFW_KEY_F9, Keyboard.KEY_F9);
        map(GLFW_KEY_F10, Keyboard.KEY_F10);
        map(GLFW_KEY_NUM_LOCK, Keyboard.KEY_NUMLOCK);
        map(GLFW_KEY_SCROLL_LOCK, Keyboard.KEY_SCROLL);
        map(GLFW_KEY_KP_7, Keyboard.KEY_NUMPAD7);
        map(GLFW_KEY_KP_8, Keyboard.KEY_NUMPAD8);
        map(GLFW_KEY_KP_9, Keyboard.KEY_NUMPAD9);
        map(GLFW_KEY_KP_SUBTRACT, Keyboard.KEY_SUBTRACT);
        map(GLFW_KEY_KP_4, Keyboard.KEY_NUMPAD4);
        map(GLFW_KEY_KP_5, Keyboard.KEY_NUMPAD5);
        map(GLFW_KEY_KP_6, Keyboard.KEY_NUMPAD6);
        map(GLFW_KEY_KP_ADD, Keyboard.KEY_ADD);
        map(GLFW_KEY_KP_1, Keyboard.KEY_NUMPAD1);
        map(GLFW_KEY_KP_2, Keyboard.KEY_NUMPAD2);
        map(GLFW_KEY_KP_3, Keyboard.KEY_NUMPAD3);
        map(GLFW_KEY_KP_0, Keyboard.KEY_NUMPAD0);
        map(GLFW_KEY_KP_DECIMAL, Keyboard.KEY_DECIMAL);
        map(GLFW_KEY_F11, Keyboard.KEY_F11);
        map(GLFW_KEY_F12, Keyboard.KEY_F12);
        map(GLFW_KEY_F13, Keyboard.KEY_F13);
        map(GLFW_KEY_F14, Keyboard.KEY_F14);
        map(GLFW_KEY_F15, Keyboard.KEY_F15);
        map(GLFW_KEY_F16, Keyboard.KEY_F16);
        map(GLFW_KEY_F17, Keyboard.KEY_F17);
        map(GLFW_KEY_F18, Keyboard.KEY_F18);
        map(GLFW_KEY_F19, Keyboard.KEY_F19);
        map(GLFW_KEY_KP_EQUAL, Keyboard.KEY_NUMPADEQUALS);
        map(GLFW_KEY_KP_ENTER, Keyboard.KEY_NUMPADENTER);
        map(GLFW_KEY_RIGHT_CONTROL, Keyboard.KEY_RCONTROL);
        map(GLFW_KEY_KP_DIVIDE, Keyboard.KEY_DIVIDE);
        map(GLFW_KEY_PRINT_SCREEN, Keyboard.KEY_SYSRQ);
        map(GLFW_KEY_RIGHT_ALT, Keyboard.KEY_RMENU);
        map(GLFW_KEY_PAUSE, Keyboard.KEY_PAUSE);
        map(GLFW_KEY_HOME, Keyboard.KEY_HOME);
        map(GLFW_KEY_UP, Keyboard.KEY_UP);
        map(GLFW_KEY_PAGE_UP, Keyboard.KEY_PRIOR);
        map(GLFW_KEY_LEFT, Keyboard.KEY_LEFT);
        map(GLFW_KEY_RIGHT, Keyboard.KEY_RIGHT);
        map(GLFW_KEY_END, Keyboard.KEY_END);
        map(GLFW_KEY_DOWN, Keyboard.KEY_DOWN);
        map(GLFW_KEY_PAGE_DOWN, Keyboard.KEY_NEXT);
        map(GLFW_KEY_INSERT, Keyboard.KEY_INSERT);
        map(GLFW_KEY_DELETE, Keyboard.KEY_DELETE);
        map(GLFW_KEY_LEFT_SUPER, Keyboard.KEY_LMETA);
        map(GLFW_KEY_RIGHT_SUPER, Keyboard.KEY_RMETA);
        map(GLFW_KEY_MENU, Keyboard.KEY_APPS);
        // ISO layouts: the extra key next to the left shift / the section key of the Mac ISO layout
        map(GLFW_KEY_WORLD_1, Keyboard.KEY_SECTION);
        map(GLFW_KEY_WORLD_2, Keyboard.KEY_BACKSLASH);
        // F20..F25 have no LWJGL 2 constant: they stay unmapped
    }

    private GLFWKeyMap() {
    }

    private static void map(int glfwKey, int lwjglKey) {
        GLFW_TO_LWJGL[glfwKey] = lwjglKey;
    }

    /** LWJGL 2 key code of a GLFW key token, 0 ({@link Keyboard#KEY_NONE}) when there is none. */
    public static int glfwToLWJGL(int glfwKey) {
        return glfwKey >= 0 && glfwKey < GLFW_TO_LWJGL.length ? GLFW_TO_LWJGL[glfwKey] : 0;
    }
}
