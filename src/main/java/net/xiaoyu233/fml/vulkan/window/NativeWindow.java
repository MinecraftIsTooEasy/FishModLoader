package net.xiaoyu233.fml.vulkan.window;

import java.nio.ByteBuffer;
import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanBackend;
import net.xiaoyu233.fml.vulkan.core.DeviceInfo;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.render.frame.FramePacer;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.ContextAttribs;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.PixelFormat;
import org.lwjgl.opengl.PixelFormatLWJGL;

/**
 * The only window API for migrated game code (5.10, 4.5); the phase-1 {@code org.lwjgl.opengl.Display} shim forwards here, so mods
 * keep working. Owns the window life cycle (GLFW window + {@link VulkanBackend}), the requested display mode, vsync and the deferred
 * fullscreen switch (WIN-01). Everything the game sees is in framebuffer pixels. Render thread only.
 *
 * <p>Methods marked "display shim" exist for {@code Display} (LWJGL 2 semantics: immediate effect, {@link LWJGLException}); migrated
 * code uses the contract methods of 5.10.
 */
public final class NativeWindow {
    private static final DisplayMode DEFAULT_WINDOW_MODE = new DisplayMode(854, 480);
    private static final DisplayMode FALLBACK_DESKTOP_MODE = DisplayMode.ofVideoMode(1920, 1080, 32, 60);

    private static boolean created;
    /** The requested mode; null until the application sets one (then it is the desktop mode). */
    private static DisplayMode currentMode;
    /** Fullscreen as requested by the game: applied to the window at the next frame boundary once the frame loop runs (WIN-01). */
    private static boolean fullscreen;
    private static boolean fullscreenApplyQueued;
    private static boolean vsyncRequested;
    private static boolean vsyncEnabled;
    private static String title = "";
    private static boolean resizable;
    private static ByteBuffer[] icons;

    private NativeWindow() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // contract (5.10)
    // ---------------------------------------------------------------------------------------------------------

    /** Creates the window and starts {@code VulkanBackend}. */
    public static void create(int width, int height, String title, boolean resizable, ByteBuffer[] icons) {
        if (created) {
            throw new IllegalStateException("The window already exists");
        }
        currentMode = new DisplayMode(Math.max(1, width), Math.max(1, height));
        NativeWindow.title = title == null ? "" : title;
        NativeWindow.resizable = resizable;
        WindowManager.setTitle(NativeWindow.title);
        WindowManager.setResizable(resizable);
        if (icons != null) {
            WindowManager.setIcons(icons);
        }
        try {
            createDisplay(new PixelFormat(), null);
        } catch (LWJGLException e) {
            throw new IllegalStateException("Failed to create the window: " + e.getMessage(), e);
        }
    }

    /** Presents if a frame is pending and polls events. */
    public static void update() {
        if (!created) {
            return;
        }
        RenderCore.present();
        pollEvents();
    }

    /** Polls the window events (the keyboard and mouse queues are filled by the GLFW callbacks). */
    public static void pollEvents() {
        if (created) {
            WindowManager.pollEvents();
        }
    }

    /** Presents the frame now (used by {@link FramePacer#endFrame()} and the display shim). */
    public static void present() {
        if (created) {
            RenderCore.present();
        }
    }

    /** True once per close request (window close button, Cmd+Q): a mod can veto it by not shutting down. */
    public static boolean isCloseRequested() {
        return created && WindowManager.consumeCloseRequest();
    }

    /** True while the window has the input focus and is not minimised. */
    public static boolean isActive() {
        return created && WindowManager.isFocused() && !WindowManager.isIconified();
    }

    /** True while the window is minimised (WIN-04: the world is not rendered then). */
    public static boolean isIconified() {
        return created && WindowManager.isIconified();
    }

    /** True once after the framebuffer changed size. */
    public static boolean wasResized() {
        return created && WindowManager.consumeResized();
    }

    /** Framebuffer width in pixels. */
    public static int width() {
        if (created && WindowManager.getFramebufferWidth() > 0) {
            return WindowManager.getFramebufferWidth();
        }
        return displayMode().getWidth();
    }

    /** Framebuffer height in pixels. */
    public static int height() {
        if (created && WindowManager.getFramebufferHeight() > 0) {
            return WindowManager.getFramebufferHeight();
        }
        return displayMode().getHeight();
    }

    /**
     * Records the wish. Once the frame loop runs the switch happens right after the next present ({@link FramePacer#runDeferredAfterPresent}),
     * never in the middle of a frame; before that (start-up, legacy loop) it is applied at once. The game then sees the new size through
     * {@link #wasResized()} and calls its resize.
     */
    public static void requestFullscreen(boolean on) {
        fullscreen = on;
        if (!created) {
            return;
        }
        if (FramePacer.loopRunning()) {
            if (!fullscreenApplyQueued) {
                fullscreenApplyQueued = true;
                FramePacer.defer(NativeWindow::applyQueuedFullscreen);
            }
        } else {
            applyFullscreenNow();
        }
    }

    /** The requested fullscreen state (what the game asked for; applied at the frame boundary). */
    public static boolean isFullscreen() {
        return fullscreen;
    }

    /**
     * WIN-02: only exclusive fullscreen is left when the window loses focus; a borderless (or monitor-sized macOS) window keeps it.
     * Replaces MITE's {@code !Display.isActive() && fullscreen} test.
     */
    public static boolean leavesFullscreenOnFocusLoss() {
        return fullscreen && created && WindowManager.isFullscreen() && !WindowManager.isFullscreenBorderless();
    }

    public static void setVSync(boolean on) {
        vsyncRequested = true;
        vsyncEnabled = on;
        if (created && VulkanBackend.isRunning()) {
            RenderCore.setVSync(on);
        }
    }

    public static void setTitle(String newTitle) {
        title = newTitle == null ? "" : newTitle;
        WindowManager.setTitle(title);
    }

    public static void destroy() {
        if (!created) {
            return;
        }
        created = false;
        tearDown();
        currentMode = null;
        fullscreen = false;
        fullscreenApplyQueued = false;
        FramePacer.reset();
        VkLog.LOGGER.info("Display destroyed");
    }

    /** {width, height, refreshRate, bitsPerPixel} of the desktop mode, in framebuffer pixels. */
    public static int[] desktopMode() {
        DisplayMode m = desktopDisplayMode();
        return new int[] {m.getWidth(), m.getHeight(), m.getFrequency(), m.getBitsPerPixel()};
    }

    public static boolean isCreated() {
        return created;
    }

    // ---------------------------------------------------------------------------------------------------------
    // display shim support
    // ---------------------------------------------------------------------------------------------------------

    /** Display shim: the current video mode of the monitor, in framebuffer pixels. */
    public static DisplayMode desktopDisplayMode() {
        try {
            WindowManager.initGLFW();
        } catch (LWJGLException e) {
            VkLog.LOGGER.warn("Cannot query the desktop display mode: {}", e.getMessage());
            return FALLBACK_DESKTOP_MODE;
        }
        WindowManager.VideoMode mode = WindowManager.getDesktopVideoMode();
        return mode == null ? FALLBACK_DESKTOP_MODE : DisplayMode.ofVideoMode(mode.width(), mode.height(), mode.bpp(), mode.refresh());
    }

    /** Display shim: the mode last set (the desktop mode before); resizing the window does not change it, like LWJGL 2. */
    public static DisplayMode displayMode() {
        return currentMode != null ? currentMode : desktopDisplayMode();
    }

    /** Display shim: sets the mode; on a live window it is applied at once. */
    public static void setDisplayMode(DisplayMode mode) throws LWJGLException {
        if (mode == null) {
            throw new IllegalArgumentException("mode must be non-null");
        }
        currentMode = mode;
        if (created) {
            if (FramePacer.loopRunning()) { // WIN-01: the live window changes at the frame boundary
                FramePacer.defer(() -> {
                    try {
                        WindowManager.applyMode(mode.getWidth(), mode.getHeight());
                    } catch (RuntimeException e) {
                        VkLog.LOGGER.warn("Deferred display mode {} failed", mode, e);
                    }
                });
                return;
            }
            try {
                WindowManager.applyMode(mode.getWidth(), mode.getHeight());
            } catch (RuntimeException e) {
                throw new LWJGLException("Could not set the display mode " + mode + ": " + e, e);
            }
        }
    }

    /** Display shim: fullscreen with LWJGL 2 semantics (immediate). */
    public static void setFullscreenNow(boolean value) throws LWJGLException {
        fullscreen = value;
        if (created) {
            DisplayMode mode = displayMode();
            WindowManager.setFullscreen(value, mode.getWidth(), mode.getHeight());
        }
    }

    /** Display shim: sets the mode and goes fullscreen at once. */
    public static void setDisplayModeAndFullscreen(DisplayMode mode) throws LWJGLException {
        if (mode == null) {
            throw new IllegalArgumentException("mode must be non-null");
        }
        currentMode = mode;
        fullscreen = true;
        if (created && FramePacer.loopRunning()) {
            FramePacer.defer(() -> {
                try {
                    WindowManager.setFullscreen(true, mode.getWidth(), mode.getHeight());
                } catch (LWJGLException e) {
                    VkLog.LOGGER.warn("Deferred fullscreen switch failed", e);
                }
            });
        } else if (created) {
            WindowManager.setFullscreen(true, mode.getWidth(), mode.getHeight());
        }
    }

    /** Display shim: {@code Display.create(...)}. */
    public static void createDisplay(PixelFormatLWJGL pixelFormat, ContextAttribs attribs) throws LWJGLException {
        if (created) {
            throw new IllegalStateException("Only one LWJGL context may be instantiated at any one time.");
        }
        VkLog.LOGGER.debug("Display.create({}, {})", pixelFormat, attribs);
        try {
            WindowManager.initGLFW();
            if (currentMode == null && !fullscreen) {
                currentMode = DEFAULT_WINDOW_MODE; // LWJGL 2 would create a desktop sized window: a game size is more useful
            }
            DisplayMode mode = displayMode();
            int width = mode.getWidth() > 0 ? mode.getWidth() : DEFAULT_WINDOW_MODE.getWidth();
            int height = mode.getHeight() > 0 ? mode.getHeight() : DEFAULT_WINDOW_MODE.getHeight();
            WindowManager.createWindow(width, height, fullscreen);
            FramePacer.applyLatencyPolicy(); // before the backend reads frames in flight / the swapchain image count
            VulkanBackend.start(WindowManager.getWindow(), WindowManager.getFramebufferWidth(), WindowManager.getFramebufferHeight());
            created = true;
            try {
                GLContext.getCapabilities(); // warms up the capability set the GL facades report
            } catch (RuntimeException e) {
                VkLog.LOGGER.warn("GLContext.getCapabilities() failed", e);
            }
            if (vsyncRequested) {
                RenderCore.setVSync(vsyncEnabled);
            }
            initControls();
            VkLog.LOGGER.info("Display created ({}x{} framebuffer pixels{})", WindowManager.getFramebufferWidth(),
                    WindowManager.getFramebufferHeight(), fullscreen ? ", fullscreen" : "");
        } catch (Throwable t) {
            VkLog.LOGGER.error("Failed to create the display", t);
            created = false;
            tearDown();
            if (t instanceof LWJGLException e) {
                throw e;
            }
            if (t instanceof VirtualMachineError e) {
                throw e;
            }
            throw new LWJGLException("Failed to create the Vulkan display: " + t, t);
        }
    }

    /** Display shim: the device name for {@code Display.getAdapter()}. */
    public static String adapterName() {
        try {
            DeviceInfo info = RenderCore.deviceInfo();
            if (info != null && info.deviceName() != null && !info.deviceName().isEmpty()) {
                return info.deviceName();
            }
        } catch (Throwable ignored) {
            // backend not running
        }
        return "Vulkan Device";
    }

    /** Display shim: the driver text for {@code Display.getVersion()}. */
    public static String driverVersion() {
        try {
            DeviceInfo info = RenderCore.deviceInfo();
            if (info != null && info.driverInfo() != null && !info.driverInfo().isEmpty()) {
                return info.driverInfo();
            }
        } catch (Throwable ignored) {
            // backend not running
        }
        return "Vulkan";
    }

    // ---------------------------------------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------------------------------------

    private static void applyQueuedFullscreen() {
        fullscreenApplyQueued = false;
        if (created) {
            applyFullscreenNow();
        }
    }

    private static void applyFullscreenNow() {
        try {
            DisplayMode mode = displayMode();
            WindowManager.setFullscreen(fullscreen, mode.getWidth(), mode.getHeight());
        } catch (LWJGLException | RuntimeException e) {
            VkLog.LOGGER.warn("Cannot switch fullscreen {}: {}", fullscreen ? "on" : "off", e.getMessage());
            fullscreen = WindowManager.isFullscreen();
        }
    }

    /** LWJGL 2 creates the mouse and the keyboard together with the display (unless the usual system properties say no). */
    private static void initControls() {
        if (Boolean.getBoolean("org.lwjgl.opengl.Display.noinput")) {
            return;
        }
        if (!Mouse.isCreated() && !Boolean.getBoolean("org.lwjgl.opengl.Display.nomouse")) {
            try {
                Mouse.create();
            } catch (LWJGLException e) {
                VkLog.LOGGER.warn("Failed to create the mouse: {}", e.getMessage());
            }
        }
        if (!Keyboard.isCreated() && !Boolean.getBoolean("org.lwjgl.opengl.Display.nokeyboard")) {
            try {
                Keyboard.create();
            } catch (LWJGLException e) {
                VkLog.LOGGER.warn("Failed to create the keyboard: {}", e.getMessage());
            }
        }
    }

    /** Input devices first (they release the cursor), then the backend, then the window and GLFW. Never throws. */
    private static void tearDown() {
        try {
            InputManager.destroyAll();
        } catch (Throwable t) {
            VkLog.LOGGER.warn("Destroying the input devices failed", t);
        }
        try {
            VulkanBackend.stop();
        } catch (Throwable t) {
            VkLog.LOGGER.error("Stopping the Vulkan backend failed", t);
        }
        try {
            WindowManager.terminate();
        } catch (Throwable t) {
            VkLog.LOGGER.error("Destroying the window failed", t);
        }
    }
}
