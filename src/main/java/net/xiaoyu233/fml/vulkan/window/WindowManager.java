package net.xiaoyu233.fml.vulkan.window;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanBackend;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import org.lwjgl.LWJGLException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * The GLFW window of the Vulkan backend (module E). Owns GLFW initialisation, the window, its callbacks, the monitors and
 * the fullscreen handling. Main (render) thread only, like every GLFW call.
 *
 * <p>Two coordinate systems exist: GLFW "screen coordinates" (window size, cursor position, video mode size) and
 * framebuffer pixels. They are equal on Windows/X11 and differ by the Retina factor on macOS, so the real ratio
 * {@code framebuffer size / window size} of the live window is the only scale used (before a window exists: the monitor
 * content scale on macOS with {@code fml.vulkan.retina}, else 1). Everything the game sees is in framebuffer pixels.
 */
public final class WindowManager {
    public static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    /** A monitor video mode in framebuffer pixels (screen size times the pixel scale, bpp 32 for 24/32 bit colour). */
    public record VideoMode(int width, int height, int bpp, int refresh) {
    }

    private static final int UNSET = Integer.MIN_VALUE;

    // ---- GLFW
    private static boolean glfwReady;
    private static int glfwGeneration;
    private static GLFWErrorCallback errorCallback;
    private static volatile String lastGlfwError;

    // ---- window state
    private static long window;
    private static int windowW;
    private static int windowH;
    private static int fbW;
    private static int fbH;
    private static boolean focused;
    private static boolean iconified;
    private static boolean closeRequested;
    private static boolean resized;

    // ---- settings made before (or after) the window exists
    private static String title = "Game";
    private static boolean resizable = true;
    private static int desiredX = UNSET;
    private static int desiredY = UNSET;
    private static long nativeCursor;
    private static final List<ByteBuffer> iconPixels = new ArrayList<>();
    private static final List<Integer> iconSizes = new ArrayList<>();

    // ---- fullscreen
    private static boolean fullscreen;
    private static boolean fullscreenBorderless;
    private static boolean restoreValid;
    private static int restoreX;
    private static int restoreY;
    private static boolean restoreDecorated = true;
    private static boolean preModePosValid;
    private static int preModeX;
    private static int preModeY;

    private WindowManager() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // GLFW lifetime
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Initialises GLFW (idempotent): error callback first (so init failures can be reported), then the Vulkan loader that
     * GLFW must share with {@code RenderCore} (honours org.lwjgl.vulkan.libname, i.e. the bundled MoltenVK), then glfwInit.
     */
    public static synchronized void initGLFW() throws LWJGLException {
        if (glfwReady) {
            return;
        }
        if (errorCallback == null) {
            errorCallback = GLFWErrorCallback.create((error, description) -> {
                String text = "GLFW error 0x" + Integer.toHexString(error) + ": " + GLFWErrorCallback.getDescription(description);
                lastGlfwError = text;
                VkLog.LOGGER.warn(text);
            });
            glfwSetErrorCallback(errorCallback);
        }
        initVulkanLoader();
        lastGlfwError = null;
        boolean ok;
        try {
            ok = glfwInit();
        } catch (RuntimeException | LinkageError e) {
            releaseErrorCallback();
            throw new LWJGLException("Failed to initialise GLFW: " + e, e);
        }
        if (!ok) {
            String reason = lastGlfwError;
            releaseErrorCallback();
            throw new LWJGLException("Failed to initialise GLFW" + (reason != null ? " (" + reason + ")" : ""));
        }
        glfwReady = true;
        glfwGeneration++;
        VkLog.LOGGER.info("GLFW {} initialised", glfwGetVersionString());
    }

    /**
     * VK is created by its class initialiser (unless org.lwjgl.vulkan.explicitInit), so never call VK.create() blindly:
     * touching VK loads the loader, a second VK.create() throws "already created". getFunctionProvider() never returns null,
     * it throws IllegalStateException while the loader is not loaded (only with explicitInit).
     */
    private static void initVulkanLoader() {
        try {
            FunctionProvider provider;
            try {
                provider = VK.getFunctionProvider();
            } catch (IllegalStateException notLoaded) {
                VK.create();
                provider = VK.getFunctionProvider();
            }
            long address = provider.getFunctionAddress("vkGetInstanceProcAddr");
            if (address != 0L) {
                GLFWVulkan.glfwInitVulkanLoader(address);
            } else {
                VkLog.LOGGER.warn("vkGetInstanceProcAddr not found in the Vulkan loader, GLFW will look for its own loader");
            }
        } catch (Throwable t) {
            // not fatal here (monitor queries do not need Vulkan): createWindow reports a missing Vulkan implementation
            VkLog.LOGGER.warn("Could not load the Vulkan loader for GLFW", t);
        }
    }

    private static void releaseErrorCallback() {
        if (errorCallback != null) {
            try {
                glfwSetErrorCallback(null);
            } catch (Throwable ignored) {
                // nothing to restore
            }
            errorCallback.free();
            errorCallback = null;
        }
    }

    public static boolean isGLFWReady() {
        return glfwReady;
    }

    /** Incremented by every glfwInit: GLFW objects (cursors) of an older generation are gone. */
    public static int getGlfwGeneration() {
        return glfwGeneration;
    }

    /** Destroys the window, frees the callbacks and terminates GLFW. Safe to call at any time. */
    public static synchronized void terminate() {
        destroyWindow();
        if (glfwReady) {
            try {
                glfwTerminate();
            } catch (Throwable t) {
                VkLog.LOGGER.warn("glfwTerminate failed", t);
            }
            glfwReady = false;
        }
        releaseErrorCallback();
        nativeCursor = 0L;
        fullscreen = false;
        restoreValid = false;
        preModePosValid = false;
    }

    // ---------------------------------------------------------------------------------------------------------
    // window
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Creates (and shows) the window so that its framebuffer has the requested size in pixels, in fullscreen when
     * {@code startFullscreen} is set (the size is then the display mode used for it).
     */
    public static void createWindow(int requestedFbWidth, int requestedFbHeight, boolean startFullscreen) throws LWJGLException {
        initGLFW();
        if (window != 0L) {
            throw new IllegalStateException("The window already exists");
        }
        if (!GLFWVulkan.glfwVulkanSupported()) {
            throw new LWJGLException("Vulkan is not available: no Vulkan loader or no installable client driver was found"
                    + (lastGlfwError != null ? " (" + lastGlfwError + ")" : ""));
        }
        long primary = glfwGetPrimaryMonitor();
        double scale = estimateScale(primary);
        int w = Math.max(1, (int) Math.ceil(requestedFbWidth / scale));
        int h = Math.max(1, (int) Math.ceil(requestedFbHeight / scale));

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, resizable ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_COCOA_RETINA_FRAMEBUFFER, VulkanConfig.retina() ? GLFW_TRUE : GLFW_FALSE);
        lastGlfwError = null;
        long handle = glfwCreateWindow(w, h, title, 0L, 0L);
        if (handle == 0L) {
            String reason = lastGlfwError;
            throw new LWJGLException("Failed to create the GLFW window" + (reason != null ? " (" + reason + ")" : ""));
        }
        window = handle;
        refreshSizes();
        placeWindow(primary);
        installCallbacks();
        applyIcons();
        if (nativeCursor != 0L) {
            glfwSetCursor(window, nativeCursor);
        }
        if (startFullscreen) {
            try {
                enterFullscreen(requestedFbWidth, requestedFbHeight);
            } catch (RuntimeException e) {
                VkLog.LOGGER.warn("Could not enter fullscreen at window creation", e);
            }
        }
        glfwShowWindow(window);
        focused = glfwGetWindowAttrib(window, GLFW_FOCUSED) == GLFW_TRUE;
        iconified = glfwGetWindowAttrib(window, GLFW_ICONIFIED) == GLFW_TRUE;
        closeRequested = false;
        resized = false;
        refreshSizes();
        if (fbW != requestedFbWidth || fbH != requestedFbHeight) {
            // e.g. odd sizes on a Retina display: let the game pick up the size it really got
            resized = true;
        }
        InputManager.onWindowCreated();
        VkLog.LOGGER.info("Window created: {}x{} screen coordinates, {}x{} framebuffer pixels (requested {}x{})",
                windowW, windowH, fbW, fbH, requestedFbWidth, requestedFbHeight);
    }

    public static synchronized void destroyWindow() {
        if (window != 0L) {
            long handle = window;
            window = 0L;
            try {
                Callbacks.glfwFreeCallbacks(handle);
                glfwDestroyWindow(handle);
            } catch (Throwable t) {
                VkLog.LOGGER.warn("Destroying the GLFW window failed", t);
            }
        }
        fbW = fbH = windowW = windowH = 0;
        focused = false;
        iconified = false;
        closeRequested = false;
        resized = false;
        fullscreen = false;
        fullscreenBorderless = false;
    }

    public static long getWindow() {
        return window;
    }

    public static boolean isCreated() {
        return window != 0L;
    }

    /**
     * Reads window and framebuffer size from GLFW (zero sizes of a minimised window are ignored). A framebuffer size that
     * differs from the last known one counts as a resize, see {@link #framebufferChanged}: GLFW applies size changes that the
     * application asks for (window size, fullscreen) synchronously on Windows and macOS, so this is also where those are seen
     * when the callback has not run yet.
     */
    private static void refreshSizes() {
        if (window == 0L) {
            return;
        }
        readWindowSize();
        int[] a = new int[1];
        int[] b = new int[1];
        glfwGetFramebufferSize(window, a, b);
        framebufferChanged(a[0], b[0]);
    }

    private static void readWindowSize() {
        int[] a = new int[1];
        int[] b = new int[1];
        glfwGetWindowSize(window, a, b);
        if (a[0] > 0 && b[0] > 0) {
            windowW = a[0];
            windowH = b[0];
        }
    }

    /**
     * The one place that stores the framebuffer size: a size different from the stored one is a resize (what
     * {@code Display.wasResized()} reports) that the renderer must follow. The comparison has to happen before the new
     * size is stored, so nothing else may write {@code fbW}/{@code fbH}. Zero sizes (a minimised window) are ignored,
     * LWJGL 2 keeps the last size then too.
     */
    private static void framebufferChanged(int width, int height) {
        if (IS_MAC && !VulkanConfig.retina() && windowW > 0 && windowH > 0) {
            // GLFW (3.5) reports the backing size (2x on a Retina display) for a Vulkan window even with
            // GLFW_COCOA_RETINA_FRAMEBUFFER off, but the CAMetalLayer then has contentsScale 1 and the swapchain is window sized:
            // the framebuffer the game renders into must be that size too (callers refresh the window size right before)
            width = windowW;
            height = windowH;
        }
        if (width <= 0 || height <= 0 || (width == fbW && height == fbH)) {
            return;
        }
        fbW = width;
        fbH = height;
        resized = true;
        if (VulkanBackend.isRunning()) {
            RenderCore.onFramebufferResized(width, height);
        }
    }

    private static void placeWindow(long monitor) {
        if (desiredX != UNSET) {
            glfwSetWindowPos(window, desiredX, desiredY);
            return;
        }
        if (monitor == 0L) {
            return;
        }
        int[] mx = new int[1];
        int[] my = new int[1];
        int[] mw = new int[1];
        int[] mh = new int[1];
        glfwGetMonitorWorkarea(monitor, mx, my, mw, mh);
        if (mw[0] <= 0 || mh[0] <= 0) {
            return;
        }
        int[] l = new int[1];
        int[] t = new int[1];
        int[] r = new int[1];
        int[] bt = new int[1];
        glfwGetWindowFrameSize(window, l, t, r, bt);
        int outerW = windowW + l[0] + r[0];
        int outerH = windowH + t[0] + bt[0];
        int x = mx[0] + Math.max(0, (mw[0] - outerW) / 2) + l[0];
        int y = my[0] + Math.max(0, (mh[0] - outerH) / 2) + t[0];
        glfwSetWindowPos(window, x, y);
    }

    // ---------------------------------------------------------------------------------------------------------
    // callbacks
    // ---------------------------------------------------------------------------------------------------------

    private static void installCallbacks() {
        glfwSetFramebufferSizeCallback(window, (win, width, height) -> {
            try {
                onFramebufferSize(width, height);
            } catch (Throwable t) {
                callbackFailed("framebuffer size", t);
            }
        });
        glfwSetWindowSizeCallback(window, (win, width, height) -> {
            try {
                if (width > 0 && height > 0) {
                    windowW = width;
                    windowH = height;
                }
            } catch (Throwable t) {
                callbackFailed("window size", t);
            }
        });
        glfwSetWindowContentScaleCallback(window, (win, xscale, yscale) -> {
            try {
                if (VulkanConfig.debug()) {
                    VkLog.LOGGER.info("Window content scale is now {}x{}", xscale, yscale);
                }
                // moving to a monitor with another scale changes the framebuffer (macOS) or the window (Windows) size
                refreshSizes();
            } catch (Throwable t) {
                callbackFailed("content scale", t);
            }
        });
        glfwSetWindowFocusCallback(window, (win, isFocused) -> focused = isFocused);
        glfwSetWindowIconifyCallback(window, (win, isIconified) -> iconified = isIconified);
        glfwSetWindowCloseCallback(window, win -> closeRequested = true);
        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            try {
                InputManager.onKey(key, scancode, action, mods);
            } catch (Throwable t) {
                callbackFailed("key", t);
            }
        });
        glfwSetCharCallback(window, (win, codepoint) -> {
            try {
                InputManager.onChar(codepoint);
            } catch (Throwable t) {
                callbackFailed("char", t);
            }
        });
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            try {
                InputManager.onMouseButton(button, action, mods);
            } catch (Throwable t) {
                callbackFailed("mouse button", t);
            }
        });
        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            try {
                InputManager.onCursorPos(xpos, ypos);
            } catch (Throwable t) {
                callbackFailed("cursor position", t);
            }
        });
        glfwSetCursorEnterCallback(window, (win, entered) -> InputManager.onCursorEnter(entered));
        glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
            try {
                InputManager.onScroll(xoffset, yoffset);
            } catch (Throwable t) {
                callbackFailed("scroll", t);
            }
        });
    }

    private static void onFramebufferSize(int width, int height) {
        // the window size first: the renderer may ask for the scale (framebuffer / window) from inside the resize notification
        readWindowSize();
        framebufferChanged(width, height);
    }

    private static void callbackFailed(String what, Throwable t) {
        VkLog.LOGGER.error("Exception in the GLFW {} callback", what, t);
    }

    /** glfwPollEvents (throttled while the window is minimised). Callbacks run inside. */
    public static void pollEvents() {
        if (!glfwReady) {
            return;
        }
        InputManager.beginPoll();
        preModePosValid = false;
        if (iconified) {
            glfwWaitEventsTimeout(0.01);
        } else {
            glfwPollEvents();
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // sizes and scale
    // ---------------------------------------------------------------------------------------------------------

    /** Framebuffer width in pixels (last valid size, 0 before the first window). */
    public static int getFramebufferWidth() {
        return fbW;
    }

    public static int getFramebufferHeight() {
        return fbH;
    }

    public static int getWindowWidth() {
        return windowW;
    }

    public static int getWindowHeight() {
        return windowH;
    }

    /** Framebuffer pixels per screen coordinate, x axis. */
    public static double getScaleX() {
        if (window != 0L && windowW > 0 && fbW > 0) {
            return (double) fbW / windowW;
        }
        return estimateScale(glfwReady ? glfwGetPrimaryMonitor() : 0L);
    }

    /** Framebuffer pixels per screen coordinate, y axis. */
    public static double getScaleY() {
        if (window != 0L && windowH > 0 && fbH > 0) {
            return (double) fbH / windowH;
        }
        return estimateScale(glfwReady ? glfwGetPrimaryMonitor() : 0L);
    }

    /** Scale to expect for a window on that monitor: only a macOS Retina framebuffer is larger than the window. */
    private static double estimateScale(long monitor) {
        if (!IS_MAC || !VulkanConfig.retina() || monitor == 0L || !glfwReady) {
            return 1.0;
        }
        float[] sx = new float[1];
        float[] sy = new float[1];
        glfwGetMonitorContentScale(monitor, sx, sy);
        return sx[0] >= 1.0f ? sx[0] : 1.0;
    }

    /** Display.wasResized: true once after the framebuffer size changed (by the user or by the application itself). */
    public static boolean consumeResized() {
        boolean r = resized;
        resized = false;
        return r;
    }

    // ---------------------------------------------------------------------------------------------------------
    // state
    // ---------------------------------------------------------------------------------------------------------

    public static boolean isFocused() {
        return window != 0L && focused;
    }

    public static boolean isIconified() {
        return window != 0L && iconified;
    }

    /** Display.isCloseRequested: true once per request, so a mod can veto the close like with LWJGL 2. */
    public static boolean consumeCloseRequest() {
        boolean r = closeRequested;
        closeRequested = false;
        return r;
    }

    static boolean isCursorOverWindow() {
        return window == 0L || glfwGetWindowAttrib(window, GLFW_HOVERED) == GLFW_TRUE;
    }

    // ---------------------------------------------------------------------------------------------------------
    // title, location, resizable, icon, cursor
    // ---------------------------------------------------------------------------------------------------------

    public static void setTitle(String newTitle) {
        title = newTitle == null ? "" : newTitle;
        if (window != 0L) {
            glfwSetWindowTitle(window, title);
        }
    }

    public static String getTitle() {
        return title;
    }

    public static void setResizable(boolean value) {
        resizable = value;
        if (window != 0L) {
            glfwSetWindowAttrib(window, GLFW_RESIZABLE, value ? GLFW_TRUE : GLFW_FALSE);
        }
    }

    public static boolean isResizable() {
        return resizable;
    }

    public static void setLocation(int x, int y) {
        desiredX = x;
        desiredY = y;
        if (window != 0L) {
            glfwSetWindowPos(window, x, y);
        }
    }

    public static int getX() {
        if (window != 0L) {
            int[] x = new int[1];
            int[] y = new int[1];
            glfwGetWindowPos(window, x, y);
            return x[0];
        }
        return desiredX == UNSET ? 0 : desiredX;
    }

    public static int getY() {
        if (window != 0L) {
            int[] x = new int[1];
            int[] y = new int[1];
            glfwGetWindowPos(window, x, y);
            return y[0];
        }
        return desiredY == UNSET ? 0 : desiredY;
    }

    /**
     * Display.setIcon: RGBA pixels, square icons only. Remembered (copied) for the window that is created later and applied
     * at once when it exists. Returns the number of icons used; GLFW has no window icon on macOS (0).
     */
    public static int setIcons(ByteBuffer[] icons) {
        iconPixels.clear();
        iconSizes.clear();
        if (icons != null) {
            for (ByteBuffer icon : icons) {
                if (icon == null) {
                    continue;
                }
                int bytes = icon.remaining();
                int size = (int) Math.round(Math.sqrt(bytes / 4.0));
                if (size <= 0 || size * size * 4 != bytes) {
                    VkLog.LOGGER.warn("Ignoring a window icon with {} bytes (not a square RGBA image)", bytes);
                    continue;
                }
                ByteBuffer copy = ByteBuffer.allocateDirect(bytes);
                copy.put(icon.duplicate());
                copy.flip();
                iconPixels.add(copy);
                iconSizes.add(size);
            }
        }
        if (IS_MAC) {
            return 0;
        }
        applyIcons();
        return iconPixels.size();
    }

    private static void applyIcons() {
        if (IS_MAC || window == 0L || iconPixels.isEmpty()) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GLFWImage.Buffer images = GLFWImage.malloc(iconPixels.size(), stack);
            for (int i = 0; i < iconPixels.size(); i++) {
                int size = iconSizes.get(i);
                images.get(i).set(size, size, iconPixels.get(i));
            }
            glfwSetWindowIcon(window, images);
        } catch (Throwable t) {
            VkLog.LOGGER.warn("Could not set the window icon", t);
        }
    }

    /** Mouse.setNativeCursor: the GLFW cursor handle (0 = the default arrow). */
    public static void setNativeCursor(long cursor) {
        nativeCursor = cursor;
        if (window != 0L) {
            glfwSetCursor(window, cursor);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // clipboard
    // ---------------------------------------------------------------------------------------------------------

    /** The clipboard text ("" when it is empty or not text). */
    public static String getClipboard() {
        if (!glfwReady) {
            return "";
        }
        try {
            String s = glfwGetClipboardString(window);
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    public static void setClipboard(String text) {
        if (!glfwReady || text == null) {
            return;
        }
        try {
            glfwSetClipboardString(window, text);
        } catch (Throwable t) {
            VkLog.LOGGER.warn("Could not set the clipboard", t);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // monitors and video modes
    // ---------------------------------------------------------------------------------------------------------

    /** The monitor the window is on (largest overlap), the primary monitor before the window exists. */
    static long currentMonitor() {
        if (!glfwReady) {
            return 0L;
        }
        long primary = glfwGetPrimaryMonitor();
        if (window == 0L) {
            return primary;
        }
        long fullscreenMonitor = glfwGetWindowMonitor(window);
        if (fullscreenMonitor != 0L) {
            return fullscreenMonitor;
        }
        PointerBuffer monitors = glfwGetMonitors();
        if (monitors == null || monitors.remaining() <= 1) {
            return primary;
        }
        int[] wx = new int[1];
        int[] wy = new int[1];
        glfwGetWindowPos(window, wx, wy);
        int[] mx = new int[1];
        int[] my = new int[1];
        long best = primary;
        long bestArea = -1L;
        for (int i = 0; i < monitors.remaining(); i++) {
            long monitor = monitors.get(i);
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            if (mode == null) {
                continue;
            }
            glfwGetMonitorPos(monitor, mx, my);
            long ox = Math.min(wx[0] + windowW, mx[0] + mode.width()) - Math.max(wx[0], mx[0]);
            long oy = Math.min(wy[0] + windowH, my[0] + mode.height()) - Math.max(wy[0], my[0]);
            long area = ox > 0 && oy > 0 ? ox * oy : 0L;
            if (area > bestArea) {
                bestArea = area;
                best = monitor;
            }
        }
        return best;
    }

    private static int bpp(GLFWVidMode mode) {
        int bits = mode.redBits() + mode.greenBits() + mode.blueBits();
        return bits >= 24 ? 32 : bits;
    }

    private static VideoMode toVideoMode(GLFWVidMode mode, double sx, double sy) {
        return new VideoMode((int) Math.round(mode.width() * sx), (int) Math.round(mode.height() * sy), bpp(mode), mode.refreshRate());
    }

    /** The current video mode of the monitor the window is on, null when there is no monitor. */
    public static VideoMode getDesktopVideoMode() {
        long monitor = currentMonitor();
        GLFWVidMode mode = monitor == 0L ? null : glfwGetVideoMode(monitor);
        if (mode == null) {
            return null;
        }
        return toVideoMode(mode, getScaleX(), getScaleY());
    }

    /** All video modes of the monitor the window is on (the desktop mode included), duplicates removed. */
    public static VideoMode[] getVideoModes() {
        long monitor = currentMonitor();
        if (monitor == 0L) {
            return new VideoMode[0];
        }
        double sx = getScaleX();
        double sy = getScaleY();
        LinkedHashSet<VideoMode> modes = new LinkedHashSet<>();
        GLFWVidMode.Buffer buffer = glfwGetVideoModes(monitor);
        if (buffer != null) {
            for (int i = 0; i < buffer.limit(); i++) {
                modes.add(toVideoMode(buffer.get(i), sx, sy));
            }
        }
        GLFWVidMode desktop = glfwGetVideoMode(monitor);
        if (desktop != null) {
            modes.add(toVideoMode(desktop, sx, sy));
        }
        return modes.toArray(new VideoMode[0]);
    }

    /**
     * Best monitor video mode for a size in screen coordinates: the current (desktop) mode when it has that size, so that
     * the refresh rate and colour depth the user has chosen stay; else the highest refresh rate of the modes with the size,
     * the desktop mode when none matches.
     */
    private static GLFWVidMode pickVideoMode(long monitor, int width, int height) {
        GLFWVidMode desktop = glfwGetVideoMode(monitor);
        if (desktop != null && desktop.width() == width && desktop.height() == height) {
            return desktop;
        }
        GLFWVidMode.Buffer buffer = glfwGetVideoModes(monitor);
        GLFWVidMode best = null;
        if (buffer != null) {
            for (int i = 0; i < buffer.limit(); i++) {
                GLFWVidMode mode = buffer.get(i);
                if (mode.width() == width && mode.height() == height && bpp(mode) >= 32
                        && (best == null || mode.refreshRate() > best.refreshRate())) {
                    best = mode;
                }
            }
        }
        return best != null ? best : desktop;
    }

    // ---------------------------------------------------------------------------------------------------------
    // fullscreen
    // ---------------------------------------------------------------------------------------------------------

    public static boolean isFullscreen() {
        return fullscreen;
    }

    /**
     * Fullscreen as a borderless window covering the monitor ({@code fml.vulkan.borderless}, default true) or as a window
     * attached to the monitor (GLFW "exclusive", the video mode only changes when the requested size differs from the
     * desktop). macOS deviates from the default on purpose (ARCHITECTURE 5.11 says borderless): a plain borderless window
     * cannot cover the menu bar and the Dock, GLFW hides both for a window attached to the monitor. So there the monitor
     * window is used unless fml.vulkan.borderless is set explicitly.
     */
    static boolean useBorderless() {
        if (IS_MAC) {
            String explicit = System.getProperty(VulkanConfig.PROP_BORDERLESS);
            if (explicit == null || explicit.isBlank()) {
                return false;
            }
        }
        return VulkanConfig.borderlessFullscreen();
    }

    /** Remembers the windowed position before the application resizes a windowed window (to restore it after fullscreen). */
    private static void rememberPreModePosition() {
        if (window != 0L && !fullscreen && !preModePosValid) {
            int[] x = new int[1];
            int[] y = new int[1];
            glfwGetWindowPos(window, x, y);
            preModeX = x[0];
            preModeY = y[0];
            preModePosValid = true;
        }
    }

    /**
     * Display.setFullscreen on a live window (before the window exists Display only records the wish and passes it to
     * {@link #createWindow}).
     *
     * @param modeWidth  framebuffer pixels of the display mode to use (exclusive fullscreen picks a matching video mode)
     * @param modeHeight dito
     */
    public static void setFullscreen(boolean value, int modeWidth, int modeHeight) throws LWJGLException {
        if (window == 0L) {
            return;
        }
        if (value == fullscreen) {
            if (value && !fullscreenBorderless) {
                applyMode(modeWidth, modeHeight);
            }
            return;
        }
        try {
            if (value) {
                enterFullscreen(modeWidth, modeHeight);
            } else {
                leaveFullscreen(modeWidth, modeHeight);
            }
        } catch (RuntimeException e) {
            throw new LWJGLException("Could not switch fullscreen " + (value ? "on" : "off") + ": " + e, e);
        } finally {
            preModePosValid = false;
        }
    }

    private static void enterFullscreen(int modeWidth, int modeHeight) {
        int[] x = new int[1];
        int[] y = new int[1];
        glfwGetWindowPos(window, x, y);
        restoreX = preModePosValid ? preModeX : x[0];
        restoreY = preModePosValid ? preModeY : y[0];
        restoreDecorated = glfwGetWindowAttrib(window, GLFW_DECORATED) == GLFW_TRUE;
        restoreValid = true;

        long monitor = currentMonitor();
        GLFWVidMode desktop = monitor == 0L ? null : glfwGetVideoMode(monitor);
        if (desktop == null) {
            throw new IllegalStateException("no monitor to go fullscreen on");
        }
        boolean borderless = useBorderless();
        if (borderless) {
            int[] mx = new int[1];
            int[] my = new int[1];
            glfwGetMonitorPos(monitor, mx, my);
            glfwSetWindowAttrib(window, GLFW_DECORATED, GLFW_FALSE);
            glfwSetWindowMonitor(window, 0L, mx[0], my[0], desktop.width(), desktop.height(), GLFW_DONT_CARE);
        } else {
            GLFWVidMode mode = pickVideoMode(monitor, (int) Math.round(modeWidth / getScaleX()), (int) Math.round(modeHeight / getScaleY()));
            glfwSetWindowMonitor(window, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
        }
        fullscreen = true;
        fullscreenBorderless = borderless;
        refreshSizes();
    }

    private static void leaveFullscreen(int modeWidth, int modeHeight) {
        double sx = getScaleX();
        double sy = getScaleY();
        int w = Math.max(1, (int) Math.ceil(modeWidth / sx));
        int h = Math.max(1, (int) Math.ceil(modeHeight / sy));
        int x = restoreValid ? restoreX : 100;
        int y = restoreValid ? restoreY : 100;
        if (!isOnAnyMonitor(x, y)) {
            x = 100;
            y = 100;
        }
        if (fullscreenBorderless) {
            glfwSetWindowAttrib(window, GLFW_DECORATED, restoreDecorated ? GLFW_TRUE : GLFW_FALSE);
        }
        glfwSetWindowMonitor(window, 0L, x, y, w, h, GLFW_DONT_CARE);
        fullscreen = false;
        fullscreenBorderless = false;
        refreshSizes();
    }

    private static boolean isOnAnyMonitor(int x, int y) {
        PointerBuffer monitors = glfwGetMonitors();
        if (monitors == null) {
            return true;
        }
        int[] mx = new int[1];
        int[] my = new int[1];
        for (int i = 0; i < monitors.remaining(); i++) {
            GLFWVidMode mode = glfwGetVideoMode(monitors.get(i));
            if (mode == null) {
                continue;
            }
            glfwGetMonitorPos(monitors.get(i), mx, my);
            if (x >= mx[0] && y >= my[0] && x < mx[0] + mode.width() && y < my[0] + mode.height()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Display.setDisplayMode on a live window: a windowed window is resized (the framebuffer gets the size in pixels),
     * exclusive fullscreen switches the video mode, a borderless window keeps covering the monitor.
     */
    public static void applyMode(int modeWidth, int modeHeight) {
        if (window == 0L) {
            return;
        }
        if (!fullscreen) {
            rememberPreModePosition();
            int w = Math.max(1, (int) Math.ceil(modeWidth / getScaleX()));
            int h = Math.max(1, (int) Math.ceil(modeHeight / getScaleY()));
            glfwSetWindowSize(window, w, h);
            refreshSizes();
        } else if (!fullscreenBorderless) {
            long monitor = currentMonitor();
            if (monitor != 0L) {
                GLFWVidMode mode = pickVideoMode(monitor, (int) Math.round(modeWidth / getScaleX()), (int) Math.round(modeHeight / getScaleY()));
                if (mode != null) {
                    glfwSetWindowMonitor(window, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
                    refreshSizes();
                }
            }
        }
    }
}
