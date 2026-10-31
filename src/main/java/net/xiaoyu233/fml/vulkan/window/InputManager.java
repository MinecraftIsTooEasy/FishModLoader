package net.xiaoyu233.fml.vulkan.window;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import org.lwjgl.input.Keyboard;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Keyboard and mouse state plus the event queues behind the LWJGL 2 {@code Keyboard}/{@code Mouse} compat classes.
 * Fed by the GLFW callbacks of {@link WindowManager}; everything runs on the render thread (the thread that calls
 * {@code glfwPollEvents}), so there is no locking. The queues are fixed size rings of primitive arrays (no allocation per
 * event); like LWJGL 2 an event that does not fit is dropped.
 *
 * <p>Semantics follow LWJGL 2.9 as the game and the mods use it:
 * <ul>
 *   <li>mouse positions are framebuffer pixels with the origin at the bottom-left, {@code [0, size-1]};</li>
 *   <li>{@code getDX/getDY/getDWheel} accumulate since the previous call, the per event deltas belong to the event;</li>
 *   <li>move and wheel events carry button -1; the wheel is delivered in multiples of 120 (fractional input accumulates);</li>
 *   <li>key repeat events only exist while {@code Keyboard.enableRepeatEvents(true)};</li>
 *   <li>the char of a key press arrives with the press event, further chars (IME commits) are separate
 *       {@code KEY_NONE} events with state false (what {@code WindowsKeyboard.handleChar} of LWJGL 2 queues; FML's
 *       {@code ChatFix} lets those through to the text fields); control keys carry their control characters.</li>
 * </ul>
 */
public final class InputManager {
    private static final int KQ_SIZE = 1024;
    private static final int KQ_MASK = KQ_SIZE - 1;
    private static final int MQ_SIZE = 1024;
    private static final int MQ_MASK = MQ_SIZE - 1;
    private static final int MOUSE_BUTTONS = 8;
    private static final int WHEEL_NOTCH = 120;

    // ---- keyboard
    private static final int[] kqKey = new int[KQ_SIZE];
    private static final char[] kqChar = new char[KQ_SIZE];
    private static final boolean[] kqState = new boolean[KQ_SIZE];
    private static final boolean[] kqRepeat = new boolean[KQ_SIZE];
    private static final long[] kqNanos = new long[KQ_SIZE];
    private static int kqHead;
    private static int kqTail;
    private static final boolean[] keyDown = new boolean[Keyboard.KEYBOARD_SIZE];
    private static boolean keyboardCreated;
    private static boolean repeatEvents;
    /** Sequence number of the last queued key press that may still receive the char of the following char callback. */
    private static int lastPressSeq;
    private static boolean lastPressValid;
    /** Set while the chars of a dropped key repeat are still to come. */
    private static boolean suppressChar;
    private static int evKey;
    private static char evChar;
    private static boolean evState;
    private static boolean evRepeat;
    private static long evNanos;

    // ---- mouse
    private static final int[] mqButton = new int[MQ_SIZE];
    private static final boolean[] mqState = new boolean[MQ_SIZE];
    private static final int[] mqX = new int[MQ_SIZE];
    private static final int[] mqY = new int[MQ_SIZE];
    private static final int[] mqDX = new int[MQ_SIZE];
    private static final int[] mqDY = new int[MQ_SIZE];
    private static final int[] mqWheel = new int[MQ_SIZE];
    private static final long[] mqNanos = new long[MQ_SIZE];
    private static int mqHead;
    private static int mqTail;
    private static final boolean[] buttonDown = new boolean[MOUSE_BUTTONS];
    private static boolean mouseCreated;
    private static boolean mouseGrabbed;
    private static boolean cursorInside = true;
    private static boolean clipMouse = !Boolean.getBoolean("org.lwjgl.input.Mouse.allowNegativeMouseCoords");
    private static int mouseX;
    private static int mouseY;
    /** Last unclipped pixel position (y up) of an absolute move, the base of the next event delta. */
    private static int lastPxX;
    private static int lastPxY;
    /** Last GLFW cursor position (screen coordinates, y down) while grabbed (virtual cursor of GLFW_CURSOR_DISABLED). */
    private static double lastRawX;
    private static double lastRawY;
    /** INP-07: the next grabbed cursor callback only re-bases the virtual cursor (its delta is a jump, not motion). */
    private static boolean resyncRaw;
    private static double fracX;
    private static double fracY;
    private static int accDX;
    private static int accDY;
    private static int accWheel;
    private static double wheelResidual;
    /** setCursorPosition while grabbed: applied when the cursor is released (screen coordinates, y down). */
    private static boolean pendingWarp;
    private static double pendingWarpX;
    private static double pendingWarpY;
    private static int evButton = -1;
    private static boolean evButtonState;
    private static int evX;
    private static int evY;
    private static int evDX;
    private static int evDY;
    private static int evWheel;
    private static long evMouseNanos;

    // ---- focus handling, counters, raw backend (OPT R3)
    /** Window within which the first button press after a focus gain is the click that refocused the window. */
    private static final long PHANTOM_CLICK_NANOS = 250_000_000L;
    private static final long SUMMARY_NANOS = 60_000_000_000L;
    private static long focusGainNanos;
    private static boolean focusGainPending;
    /** Buttons whose press was dropped by the phantom guard; their release is dropped too. */
    private static int suppressedButtons;
    private static volatile BooleanSupplier screenOpenProbe;
    private static volatile int keyOverflow;
    private static volatile int mouseOverflow;
    private static volatile int phantomDropped;
    private static long nextSummaryNanos;
    private static volatile RawInputBackend backend;
    private static final long[] backendDelta = new long[2];

    /**
     * H11 hook for a native raw input backend (R4). Called on the render thread once per poll, before glfwPollEvents.
     * Implementations write the motion accumulated since the previous call, in screen units with y up positive (the unit of
     * {@code Mouse.getDX/getDY}), into {@code dxdy[0]} (x) and {@code dxdy[1]} (y) and return true when there was any. While a
     * backend is set and the cursor is grabbed the GLFW cursor deltas are ignored (no double counting).
     */
    public interface RawInputBackend {
        boolean poll(long[] dxdy);
    }

    public static void setBackend(RawInputBackend b) {
        backend = b;
    }

    public static RawInputBackend getBackend() {
        return backend;
    }

    /** Pulls the accumulated deltas of the backend into the mouse queue; no-op without backend or while not grabbed. */
    static void consumeBackendDeltas() {
        RawInputBackend b = backend;
        if (b == null || !mouseCreated) {
            return;
        }
        backendDelta[0] = 0L;
        backendDelta[1] = 0L;
        boolean any;
        try {
            any = b.poll(backendDelta);
        } catch (Throwable t) {
            backend = null;
            VkLog.LOGGER.warn("RawInputBackend failed, falling back to GLFW cursor deltas", t);
            return;
        }
        if (!any || !mouseGrabbed) {
            return;
        }
        int dx = (int) backendDelta[0];
        int dy = (int) backendDelta[1];
        if (dx == 0 && dy == 0) {
            return;
        }
        int fbW = WindowManager.getFramebufferWidth();
        int fbH = WindowManager.getFramebufferHeight();
        mouseX = clamp(mouseX + (int) Math.round(dx * WindowManager.getScaleX()), 0, fbW - 1);
        mouseY = clamp(mouseY + (int) Math.round(dy * WindowManager.getScaleY()), 0, fbH - 1);
        accDX += dx;
        accDY += dy;
        queueMove(mouseX, mouseY, dx, dy);
    }

    /** Optional probe telling whether a GUI screen is open (phantom click guard); default: screen open == cursor not grabbed. */
    public static void setScreenOpenProbe(BooleanSupplier probe) {
        screenOpenProbe = probe;
    }

    public static int getKeyOverflowCount() {
        return keyOverflow;
    }

    public static int getMouseOverflowCount() {
        return mouseOverflow;
    }

    /** Ring overflow plus phantom-click drops, total. */
    public static int getDroppedEventCount() {
        return keyOverflow + mouseOverflow + phantomDropped;
    }

    public static int getPhantomClickDropCount() {
        return phantomDropped;
    }

    public static String statsLine() {
        return "input: keyOverflow=" + keyOverflow + " mouseOverflow=" + mouseOverflow + " phantomClicks=" + phantomDropped
                + " backlog=" + getNumKeyboardEvents() + "/" + (mqTail - mqHead);
    }

    private static void maybeLogSummary() {
        long now = System.nanoTime();
        if (nextSummaryNanos == 0L) {
            nextSummaryNanos = now + SUMMARY_NANOS;
        } else if (now - nextSummaryNanos >= 0L) {
            nextSummaryNanos = now + SUMMARY_NANOS;
            if (VkLog.LOGGER.isDebugEnabled()) {
                VkLog.LOGGER.debug(statsLine());
            }
        }
    }

    /**
     * Focus callback. Loss: every pressed key/button is released with a synthetic release event so nothing sticks (the
     * {@code KeyBinding.unPressAllKeys} situation of {@code setIngameNotInFocus}), unless {@code input.restoreHeldKeys} is on, in
     * which case the state is kept and reconciled with the physical state on regain. Gain: resync with glfwGetKey/glfwGetMouseButton
     * (never emits press events), arm the phantom click guard.
     */
    public static void onFocus(boolean focused) {
        if (!focused) {
            focusGainPending = false;
            if (!VulkanConfig.inputRestoreHeldKeys()) {
                releaseAll();
            }
            return;
        }
        focusGainNanos = System.nanoTime();
        focusGainPending = true;
        suppressedButtons = 0;
        if (VulkanConfig.inputRestoreHeldKeys()) {
            restoreHeld();
        }
    }

    private static void releaseAll() {
        if (keyboardCreated) {
            for (int k = 0; k < keyDown.length; k++) {
                if (keyDown[k]) {
                    keyDown[k] = false;
                    kqPut(k, false, (char) 0, false);
                }
            }
            lastPressValid = false;
        }
        if (mouseCreated) {
            for (int b = 0; b < MOUSE_BUTTONS; b++) {
                if (buttonDown[b]) {
                    buttonDown[b] = false;
                    mqPut(b, false, mouseX, mouseY, 0, 0, 0);
                }
            }
        }
    }

    /** Resync with the physical state: released while unfocused -> synthetic release; still held -> stays down. */
    private static void restoreHeld() {
        long window = WindowManager.getWindow();
        if (window == 0L) {
            return;
        }
        if (keyboardCreated) {
            for (int g = GLFW_KEY_SPACE; g <= GLFW_KEY_LAST; g++) {
                int k = GLFWKeyMap.glfwToLWJGL(g);
                if (k <= 0 || k >= keyDown.length) {
                    continue;
                }
                boolean phys = glfwGetKey(window, g) == GLFW_PRESS;
                if (keyDown[k] && !phys) {
                    keyDown[k] = false;
                    kqPut(k, false, (char) 0, false);
                } else if (!keyDown[k] && phys) {
                    keyDown[k] = true; // state only, never a press event
                }
            }
            lastPressValid = false;
        }
        if (mouseCreated) {
            for (int b = 0; b < MOUSE_BUTTONS; b++) {
                boolean phys = glfwGetMouseButton(window, b) == GLFW_PRESS;
                if (buttonDown[b] && !phys) {
                    buttonDown[b] = false;
                    mqPut(b, false, mouseX, mouseY, 0, 0, 0);
                }
            }
        }
    }

    private static boolean screenOpen() {
        BooleanSupplier p = screenOpenProbe;
        if (p != null) {
            try {
                return p.getAsBoolean();
            } catch (RuntimeException e) {
                return !mouseGrabbed;
            }
        }
        return !mouseGrabbed;
    }

    // ---- raw mouse toggle (fml.vulkan.rawMouse)

    /** False where GLFW has no raw motion (macOS). */
    public static boolean isRawMouseSupported() {
        if (!WindowManager.isGLFWReady()) {
            return false;
        }
        try {
            return glfwRawMouseMotionSupported();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Button label: Raw Mouse: ON / OFF / Unsupported. */
    public static String rawMouseLabel() {
        if (!isRawMouseSupported()) {
            return "Raw Mouse: Unsupported";
        }
        return "Raw Mouse: " + (VulkanConfig.rawMouse() ? "ON" : "OFF");
    }

    /** Flips fml.vulkan.rawMouse at runtime, persists it and re-applies the grab. No-op when unsupported. */
    public static void toggleRawMouse() {
        if (!isRawMouseSupported()) {
            return;
        }
        boolean value = !VulkanConfig.rawMouse();
        System.setProperty(VulkanConfig.PROP_RAW_MOUSE, Boolean.toString(value));
        persistRawMouse(value);
        if (mouseCreated && mouseGrabbed) {
            applyGrab(true);
        }
    }

    private static void persistRawMouse(boolean value) {
        try {
            java.nio.file.Path path = VulkanConfig.gameDir().resolve("config/fml-vulkan.properties");
            java.util.List<String> lines = java.nio.file.Files.exists(path)
                    ? new java.util.ArrayList<>(java.nio.file.Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8))
                    : new java.util.ArrayList<>();
            String entry = "rawMouse=" + value;
            boolean done = false;
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (t.startsWith("rawMouse=") || t.startsWith("#rawMouse=")) {
                    if (!done) {
                        lines.set(i, entry);
                        done = true;
                    } else if (t.startsWith("rawMouse=")) {
                        lines.remove(i--);
                    }
                }
            }
            if (!done) {
                lines.add(entry);
            }
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.write(path, lines, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException | RuntimeException e) {
            VkLog.LOGGER.warn("Cannot persist rawMouse: {}", e.toString());
        }
    }

    private InputManager() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // lifecycle
    // ---------------------------------------------------------------------------------------------------------

    public static void createKeyboard() {
        if (keyboardCreated) {
            return;
        }
        resetKeyboard();
        keyboardCreated = true;
    }

    public static void createMouse() {
        if (mouseCreated) {
            return;
        }
        resetMouse();
        mouseCreated = true;
        syncCursorFromGlfw();
        if (mouseGrabbed) {
            applyGrab(true);
        }
    }

    public static void destroyKeyboard() {
        keyboardCreated = false;
        resetKeyboard();
    }

    public static void destroyMouse() {
        if (mouseCreated && mouseGrabbed) {
            applyGrab(false);
        }
        mouseCreated = false;
        mouseGrabbed = false;
        pendingWarp = false;
        resetMouse();
    }

    /** Display.destroy: both devices go away with the window. */
    public static void destroyAll() {
        destroyMouse();
        destroyKeyboard();
        cursorInside = true;
    }

    public static boolean isKeyboardCreated() {
        return keyboardCreated;
    }

    public static boolean isMouseCreated() {
        return mouseCreated;
    }

    /** Called before every glfwPollEvents: key and char callbacks of one key stroke always arrive in the same poll. */
    static void beginPoll() {
        maybeLogSummary();
        consumeBackendDeltas();
        lastPressValid = false;
        suppressChar = false;
    }

    private static void resetKeyboard() {
        kqHead = kqTail = 0;
        Arrays.fill(keyDown, false);
        lastPressValid = false;
        suppressChar = false;
        evKey = 0;
        evChar = 0;
        evState = false;
        evRepeat = false;
        evNanos = 0L;
    }

    private static void resetMouse() {
        mqHead = mqTail = 0;
        Arrays.fill(buttonDown, false);
        accDX = accDY = accWheel = 0;
        fracX = fracY = 0.0;
        wheelResidual = 0.0;
        evButton = -1;
        evButtonState = false;
        evDX = evDY = evWheel = 0;
        evMouseNanos = 0L;
    }

    // ---------------------------------------------------------------------------------------------------------
    // GLFW callbacks
    // ---------------------------------------------------------------------------------------------------------

    public static void onKey(int key, int scancode, int action, int mods) {
        if (!keyboardCreated) {
            return;
        }
        int k = GLFWKeyMap.glfwToLWJGL(key);
        if (k == 0) {
            // no LWJGL 2 code: such a key does not exist for the game (it would look like key 0)
            lastPressValid = false;
            return;
        }
        switch (action) {
            case GLFW_PRESS -> {
                suppressChar = false;
                if (k == Keyboard.KEY_L && keyDown[Keyboard.KEY_F3]) {
                    cycleReflex(); // F3 + L: Reflex off -> on -> boost (the L press still reaches the game, it has no F3 meaning there)
                }
                keyDown[k] = true;
                queuePress(k, controlChar(key, scancode, mods), false);
            }
            case GLFW_REPEAT -> {
                keyDown[k] = true;
                if (repeatEvents) {
                    suppressChar = false;
                    queuePress(k, controlChar(key, scancode, mods), true);
                } else {
                    // the key stays down but there is no event; neither has the char GLFW sends for this repeat
                    lastPressValid = false;
                    suppressChar = true;
                }
            }
            case GLFW_RELEASE -> {
                suppressChar = false;
                keyDown[k] = false;
                lastPressValid = false;
                kqPut(k, false, (char) 0, false);
            }
            default -> {
            }
        }
    }

    /** A Unicode code point from the GLFW char callback (supplementary code points are split into surrogates like Java chars). */
    public static void onChar(int codepoint) {
        if (!keyboardCreated || suppressChar || codepoint <= 0) {
            return;
        }
        if (Character.isBmpCodePoint(codepoint)) {
            deliverChar((char) codepoint);
        } else if (Character.isValidCodePoint(codepoint)) {
            deliverChar(Character.highSurrogate(codepoint));
            deliverChar(Character.lowSurrogate(codepoint));
        }
    }

    public static void onMouseButton(int button, int action, int mods) {
        if (!mouseCreated || button < 0 || button >= MOUSE_BUTTONS) {
            return;
        }
        boolean down = action == GLFW_PRESS;
        if (down && focusGainPending) {
            focusGainPending = false;
            if (System.nanoTime() - focusGainNanos < PHANTOM_CLICK_NANOS && !screenOpen()) {
                // the click that refocused the window must not attack/place
                suppressedButtons |= 1 << button;
                phantomDropped++;
                return;
            }
        } else if (!down && (suppressedButtons & (1 << button)) != 0) {
            suppressedButtons &= ~(1 << button);
            return;
        }
        buttonDown[button] = down;
        if (down && button == 0 && mouseGrabbed) {
            net.xiaoyu233.fml.vulkan.core.LowLatency.triggerFlash(); // Reflex latency analyzer / flash indicator (NVIDIA only)
        }
        mqPut(button, down, mouseX, mouseY, 0, 0, 0);
    }

    private static void cycleReflex() {
        String status = net.xiaoyu233.fml.vulkan.core.LowLatency.cycleMode();
        try {
            net.minecraft.Minecraft mc = net.minecraft.Minecraft.getMinecraft();
            if (mc != null && mc.ingameGUI != null) {
                mc.ingameGUI.getChatGUI().printChatMessage("Reflex: " + status);
            }
        } catch (RuntimeException e) {
            VkLog.LOGGER.debug("Reflex toggle message not shown", e);
        }
    }

    public static void onCursorPos(double xpos, double ypos) {
        if (!mouseCreated) {
            return;
        }
        double sx = WindowManager.getScaleX();
        double sy = WindowManager.getScaleY();
        int fbW = WindowManager.getFramebufferWidth();
        int fbH = WindowManager.getFramebufferHeight();
        int newX;
        int newY;
        int dx;
        int dy;
        if (mouseGrabbed) {
            // GLFW_CURSOR_DISABLED: the callback reports an unbounded virtual cursor; its differences are the raw motion
            // in screen units (not scaled, like the LWJGL 2 deltas), y up is positive. Fractions are carried over so slow
            // motion is not lost.
            if (resyncRaw) {
                resyncRaw = false;
                lastRawX = xpos;
                lastRawY = ypos;
            }
            if (backend != null) {
                lastRawX = xpos;
                lastRawY = ypos;
                return;
            }
            fracX += xpos - lastRawX;
            fracY += lastRawY - ypos;
            lastRawX = xpos;
            lastRawY = ypos;
            dx = (int) fracX;
            dy = (int) fracY;
            fracX -= dx;
            fracY -= dy;
            newX = clamp(mouseX + (int) Math.round(dx * sx), 0, fbW - 1);
            newY = clamp(mouseY + (int) Math.round(dy * sy), 0, fbH - 1);
        } else {
            int rawX = (int) Math.floor(xpos * sx);
            int rawY = fbH - 1 - (int) Math.floor(ypos * sy);
            dx = rawX - lastPxX;
            dy = rawY - lastPxY;
            lastPxX = rawX;
            lastPxY = rawY;
            newX = clipMouse ? clamp(rawX, 0, fbW - 1) : rawX;
            newY = clipMouse ? clamp(rawY, 0, fbH - 1) : rawY;
        }
        if (dx == 0 && dy == 0 && newX == mouseX && newY == mouseY) {
            return;
        }
        mouseX = newX;
        mouseY = newY;
        accDX += dx;
        accDY += dy;
        queueMove(newX, newY, dx, dy);
    }

    public static void onCursorEnter(boolean entered) {
        cursorInside = entered;
    }

    public static void onScroll(double xoffset, double yoffset) {
        if (!mouseCreated) {
            return;
        }
        wheelResidual += yoffset * WHEEL_NOTCH;
        int notches = (int) (wheelResidual / WHEEL_NOTCH);
        if (notches == 0) {
            return;
        }
        int delta = notches * WHEEL_NOTCH;
        wheelResidual -= delta;
        accWheel += delta;
        mqPut(-1, false, mouseX, mouseY, 0, 0, delta);
    }

    // ---------------------------------------------------------------------------------------------------------
    // keyboard
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The control character LWJGL 2 delivers for a key press (GLFW never sends control characters to the char callback):
     * 1..26 for Ctrl+letter (Cmd+letter on macOS, where that is the shortcut key), 13/8/9/27 for Enter/Backspace/Tab/Escape.
     * The text fields of the game do Ctrl+A/C/V/X by those characters.
     */
    private static char controlChar(int glfwKey, int scancode, int mods) {
        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0 || (WindowManager.IS_MAC && (mods & GLFW_MOD_SUPER) != 0);
        // glfwKey is the position of the key on a US keyboard; only the printable keys have a letter that depends on the layout
        if (ctrl && (mods & GLFW_MOD_ALT) == 0 && glfwKey >= GLFW_KEY_SPACE && glfwKey <= GLFW_KEY_GRAVE_ACCENT) {
            char letter = keyLetter(layoutKeyName(glfwKey, scancode), glfwKey);
            if (letter != 0) {
                return (char) (letter - 'a' + 1);
            }
        }
        return switch (glfwKey) {
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> (char) 13;
            case GLFW_KEY_BACKSPACE -> (char) 8;
            case GLFW_KEY_TAB -> (char) 9;
            case GLFW_KEY_ESCAPE -> (char) 27;
            default -> (char) 0;
        };
    }

    /** What the key is called in the active keyboard layout ("a", ","...), null when GLFW does not know. */
    private static String layoutKeyName(int glfwKey, int scancode) {
        if (!WindowManager.isGLFWReady()) {
            return null;
        }
        try {
            return glfwGetKeyName(glfwKey, scancode);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * The lower case Latin letter a key stands for, 0 when it has none. LWJGL 2 asks the keyboard layout (ToUnicode), so
     * Ctrl+A is the key labelled A on an AZERTY keyboard (the physical Q position) and Ctrl+C the key labelled C on Dvorak.
     * Non-Latin layouts (Cyrillic, Greek...) have no letter to offer, there the shortcut stays on the physical (US) position
     * of the key, so Ctrl+C is still the C of the US layout; punctuation and digits never make a control character.
     *
     * @param layoutName the name GLFW gives the key in the active layout, may be null
     * @param glfwKey    the GLFW key token, the position of the key on a US keyboard
     */
    static char keyLetter(String layoutName, int glfwKey) {
        if (layoutName != null && !layoutName.isEmpty()) {
            int cp = layoutName.codePointAt(0);
            if (layoutName.length() == Character.charCount(cp)) {
                if (cp >= 'a' && cp <= 'z') {
                    return (char) cp;
                }
                if (cp >= 'A' && cp <= 'Z') {
                    return (char) (cp + ('a' - 'A'));
                }
                if (!Character.isLetter(cp)) {
                    return 0;
                }
            }
        }
        return glfwKey >= GLFW_KEY_A && glfwKey <= GLFW_KEY_Z ? (char) ('a' + glfwKey - GLFW_KEY_A) : 0;
    }

    private static void queuePress(int key, char ch, boolean repeat) {
        if (kqPut(key, true, ch, repeat)) {
            lastPressSeq = kqTail - 1;
            lastPressValid = true;
        } else {
            lastPressValid = false;
        }
    }

    private static void deliverChar(char c) {
        // merge into the key press that is still the newest queued event and has no char yet, else it is an extra event
        if (lastPressValid && lastPressSeq == kqTail - 1 && lastPressSeq - kqHead >= 0 && kqChar[lastPressSeq & KQ_MASK] == 0) {
            kqChar[lastPressSeq & KQ_MASK] = c;
            lastPressValid = false;
            return;
        }
        lastPressValid = false;
        // like LWJGL 2: no key, no state, only a character (a state of true would also press the key bindings of key 0)
        kqPut(Keyboard.KEY_NONE, false, c, false);
    }

    private static boolean kqPut(int key, boolean state, char ch, boolean repeat) {
        if (kqTail - kqHead >= KQ_SIZE) {
            keyOverflow++;
            return false;
        }
        int i = kqTail & KQ_MASK;
        kqKey[i] = key;
        kqState[i] = state;
        kqChar[i] = ch;
        kqRepeat[i] = repeat;
        kqNanos[i] = System.nanoTime();
        kqTail++;
        return true;
    }

    public static boolean isKeyDown(int key) {
        return key >= 0 && key < keyDown.length && keyboardCreated && keyDown[key];
    }

    public static boolean nextKeyboardEvent() {
        if (kqHead == kqTail) {
            return false;
        }
        int i = kqHead & KQ_MASK;
        evKey = kqKey[i];
        evChar = kqChar[i];
        evState = kqState[i];
        evRepeat = kqRepeat[i];
        evNanos = kqNanos[i];
        kqHead++;
        return true;
    }

    public static int getNumKeyboardEvents() {
        return kqTail - kqHead;
    }

    public static char getKeyboardEventCharacter() {
        return evChar;
    }

    public static int getKeyboardEventKey() {
        return evKey;
    }

    public static boolean getKeyboardEventKeyState() {
        return evState;
    }

    public static long getKeyboardEventNanoseconds() {
        return evNanos;
    }

    public static boolean isKeyboardRepeatEvent() {
        return evRepeat;
    }

    public static void enableRepeatEvents(boolean enable) {
        repeatEvents = enable;
    }

    public static boolean areRepeatEventsEnabled() {
        return repeatEvents;
    }

    // ---------------------------------------------------------------------------------------------------------
    // mouse
    // ---------------------------------------------------------------------------------------------------------

    private static boolean mqPut(int button, boolean state, int x, int y, int dx, int dy, int wheel) {
        if (mqTail - mqHead >= MQ_SIZE) {
            mouseOverflow++;
            return false;
        }
        int i = mqTail & MQ_MASK;
        mqButton[i] = button;
        mqState[i] = state;
        mqX[i] = x;
        mqY[i] = y;
        mqDX[i] = dx;
        mqDY[i] = dy;
        mqWheel[i] = wheel;
        mqNanos[i] = System.nanoTime();
        mqTail++;
        return true;
    }

    /** A move event; moves that directly follow an unread move event are merged into it (same total delta, newest position). */
    private static void queueMove(int x, int y, int dx, int dy) {
        if (mqTail - mqHead > 0) {
            int last = (mqTail - 1) & MQ_MASK;
            if (mqButton[last] == -1 && mqWheel[last] == 0) {
                mqX[last] = x;
                mqY[last] = y;
                mqDX[last] += dx;
                mqDY[last] += dy;
                mqNanos[last] = System.nanoTime();
                return;
            }
        }
        mqPut(-1, false, x, y, dx, dy, 0);
    }

    /**
     * A synthetic relative mouse move, queued like a GLFW cursor event (automation and the latency measurement of the devtest
     * commands); returns the event time ({@code System.nanoTime()}) or 0 when the mouse does not exist. Any thread.
     */
    public static synchronized long injectMouseMove(int dx, int dy) {
        if (!mouseCreated) {
            return 0L;
        }
        accDX += dx;
        accDY += dy;
        queueMove(mouseX, mouseY, dx, dy);
        return mqNanos[(mqTail - 1) & MQ_MASK];
    }

    public static boolean nextMouseEvent() {
        if (mqHead == mqTail) {
            return false;
        }
        int i = mqHead & MQ_MASK;
        evButton = mqButton[i];
        evButtonState = mqState[i];
        evX = mqX[i];
        evY = mqY[i];
        evDX = mqDX[i];
        evDY = mqDY[i];
        evWheel = mqWheel[i];
        evMouseNanos = mqNanos[i];
        mqHead++;
        return true;
    }

    public static boolean isMouseButtonDown(int button) {
        return mouseCreated && button >= 0 && button < MOUSE_BUTTONS && buttonDown[button];
    }

    public static int getMouseX() {
        return mouseX;
    }

    public static int getMouseY() {
        return mouseY;
    }

    public static int getMouseDX() {
        int v = accDX;
        accDX = 0;
        return v;
    }

    public static int getMouseDY() {
        int v = accDY;
        accDY = 0;
        return v;
    }

    public static int getMouseDWheel() {
        int v = accWheel;
        accWheel = 0;
        return v;
    }

    public static int getMouseEventButton() {
        return evButton;
    }

    public static boolean getMouseEventButtonState() {
        return evButtonState;
    }

    public static int getMouseEventDX() {
        return evDX;
    }

    public static int getMouseEventDY() {
        return evDY;
    }

    public static int getMouseEventX() {
        return evX;
    }

    public static int getMouseEventY() {
        return evY;
    }

    public static int getMouseEventDWheel() {
        return evWheel;
    }

    public static long getMouseEventNanoseconds() {
        return evMouseNanos;
    }

    public static boolean isClipMouseCoordinatesToWindow() {
        return clipMouse;
    }

    public static void setClipMouseCoordinatesToWindow(boolean clip) {
        clipMouse = clip;
    }

    public static boolean isMouseGrabbed() {
        return mouseGrabbed;
    }

    public static boolean isMouseInsideWindow() {
        return mouseGrabbed || cursorInside;
    }

    /**
     * INP-07: drops the motion collected so far and the one of the next grabbed cursor callback (grab, focus gain, resize and
     * fullscreen switches make GLFW report a jump of the virtual cursor). Button/key events and the position are untouched.
     */
    public static void discardMotion() {
        accDX = accDY = 0;
        fracX = fracY = 0.0;
        resyncRaw = true;
    }

    /**
     * INP-09: drops the key and mouse events that are still queued (they were queued while the game handled them, e.g. the second
     * press of the key that opened a GUI). Key and button states and the accumulated motion stay.
     */
    public static void drainPending() {
        kqHead = kqTail;
        mqHead = mqTail;
        lastPressValid = false;
    }

    /** Called by the window right after it is created (state requested earlier is applied now). */
    static void onWindowCreated() {
        cursorInside = WindowManager.isCursorOverWindow();
        if (mouseCreated) {
            syncCursorFromGlfw();
            if (mouseGrabbed) {
                applyGrab(true);
            }
        }
    }

    public static void setMouseGrabbed(boolean grab) {
        if (grab == mouseGrabbed) {
            return;
        }
        mouseGrabbed = grab;
        if (mouseCreated) {
            applyGrab(grab);
        }
    }

    private static void startRawBackend(long window) {
        if (backend != null || !RawInputWin32.available()) {
            return;
        }
        try {
            if (RawInputWin32.start(window)) {
                backend = d -> {
                    RawInputWin32.poll();
                    int x = RawInputWin32.takeDeltaX();
                    int y = -RawInputWin32.takeDeltaY();
                    d[0] = x;
                    d[1] = y;
                    return x != 0 || y != 0;
                };
            }
        } catch (Throwable t) {
            VkLog.LOGGER.warn("raw input backend start failed", t);
        }
    }

    private static void stopRawBackend() {
        if (backend != null && RawInputWin32.isActive()) {
            backend = null;
            RawInputWin32.stop();
        }
    }

    /** Grabs or releases the cursor in GLFW (needs a window); drops the motion collected so far like LWJGL 2 does. */
    public static void applyGrab(boolean grab) {
        long window = WindowManager.getWindow();
        if (window == 0L) {
            return;
        }
        if (grab) {
            startRawBackend(window);
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            if (glfwRawMouseMotionSupported()) {
                glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, VulkanConfig.rawMouse() ? GLFW_TRUE : GLFW_FALSE);
            }
            pendingWarp = false;
            double[] cx = new double[1];
            double[] cy = new double[1];
            glfwGetCursorPos(window, cx, cy);
            lastRawX = cx[0];
            lastRawY = cy[0];
        } else {
            stopRawBackend();
            if (glfwRawMouseMotionSupported()) {
                glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_FALSE);
            }
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            if (pendingWarp) {
                // GLFW restored the cursor to where it was grabbed; LWJGL 2 puts it where setCursorPosition asked for
                pendingWarp = false;
                glfwSetCursorPos(window, pendingWarpX, pendingWarpY);
            }
            syncCursorFromGlfw();
        }
        accDX = accDY = 0;
        fracX = fracY = 0.0;
        resyncRaw = true;
    }

    /** Reads the cursor from GLFW into the position state (no event, no delta). */
    private static void syncCursorFromGlfw() {
        long window = WindowManager.getWindow();
        if (window == 0L) {
            return;
        }
        double[] cx = new double[1];
        double[] cy = new double[1];
        glfwGetCursorPos(window, cx, cy);
        lastRawX = cx[0];
        lastRawY = cy[0];
        int fbW = WindowManager.getFramebufferWidth();
        int fbH = WindowManager.getFramebufferHeight();
        lastPxX = (int) Math.floor(cx[0] * WindowManager.getScaleX());
        lastPxY = fbH - 1 - (int) Math.floor(cy[0] * WindowManager.getScaleY());
        mouseX = clipMouse ? clamp(lastPxX, 0, fbW - 1) : lastPxX;
        mouseY = clipMouse ? clamp(lastPxY, 0, fbH - 1) : lastPxY;
    }

    /** Mouse.setCursorPosition: framebuffer pixels, origin bottom-left. */
    public static void setCursorPosition(int x, int y) {
        mouseX = x;
        mouseY = y;
        lastPxX = x;
        lastPxY = y;
        long window = WindowManager.getWindow();
        if (window == 0L) {
            return;
        }
        double gx = (x + 0.5) / WindowManager.getScaleX();
        double gy = (WindowManager.getFramebufferHeight() - 1 - y + 0.5) / WindowManager.getScaleY();
        if (mouseGrabbed) {
            // while GLFW_CURSOR_DISABLED glfwSetCursorPos only moves the virtual cursor and the real cursor comes back to
            // where it was grabbed: remember the wish and warp when the cursor is released
            pendingWarp = true;
            pendingWarpX = gx;
            pendingWarpY = gy;
        } else {
            glfwSetCursorPos(window, gx, gy);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        if (hi < lo) {
            return lo;
        }
        return v < lo ? lo : Math.min(v, hi);
    }
}
