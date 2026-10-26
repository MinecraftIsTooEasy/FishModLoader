package org.lwjgl.opengl;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanBackend;
import net.xiaoyu233.fml.vulkan.core.DeviceInfo;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.window.FrameLimiter;
import net.xiaoyu233.fml.vulkan.window.InputManager;
import net.xiaoyu233.fml.vulkan.window.WindowManager;
import org.lwjgl.LWJGLException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.nio.ByteBuffer;

/**
 * LWJGL 2 API: the window of the game, on GLFW + the Vulkan backend (module E). The public API is the one of LWJGL 2.9.0
 * ({@code Display.create(PixelFormatLWJGL, org.lwjgl.opengles.ContextAttribs)} excepted: there is no GLES).
 *
 * <p>Everything the game sees is in framebuffer pixels: {@link #getWidth()}/{@link #getHeight()}, the {@link DisplayMode}s
 * and what {@link #getDisplayMode()} returns after {@link #setDisplayMode}. A mode is the size of the framebuffer, on a
 * Retina display the window is half as large in screen coordinates. Render thread only.
 */
public final class Display {
    private static final DisplayMode DEFAULT_WINDOW_MODE = new DisplayMode(854, 480);
    private static final DisplayMode FALLBACK_DESKTOP_MODE = new DisplayMode(1920, 1080, 32, 60);
    private static final Drawable DRAWABLE = new DisplayDrawable();

    private static boolean created;
    /** The requested mode; null until the application sets one (then it is the desktop mode). */
    private static DisplayMode currentMode;
    /** Fullscreen as requested by the application (it is applied when the window is created). */
    private static boolean fullscreen;
    private static boolean vsyncRequested;
    private static boolean vsyncEnabled;

    private Display() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // display modes
    // ---------------------------------------------------------------------------------------------------------

    public static Drawable getDrawable() {
        return DRAWABLE;
    }

    public static DisplayMode[] getAvailableDisplayModes() throws LWJGLException {
        WindowManager.initGLFW();
        WindowManager.VideoMode[] modes = WindowManager.getVideoModes();
        DisplayMode[] result = new DisplayMode[modes.length];
        for (int i = 0; i < modes.length; i++) {
            result[i] = new DisplayMode(modes[i].width(), modes[i].height(), modes[i].bpp(), modes[i].refresh());
        }
        return result;
    }

    /** The current video mode of the monitor, in framebuffer pixels (part of {@link #getAvailableDisplayModes()}). */
    public static DisplayMode getDesktopDisplayMode() {
        try {
            WindowManager.initGLFW();
        } catch (LWJGLException e) {
            VkLog.LOGGER.warn("Cannot query the desktop display mode: {}", e.getMessage());
            return FALLBACK_DESKTOP_MODE;
        }
        WindowManager.VideoMode mode = WindowManager.getDesktopVideoMode();
        return mode == null ? FALLBACK_DESKTOP_MODE : new DisplayMode(mode.width(), mode.height(), mode.bpp(), mode.refresh());
    }

    /**
     * The mode last set with {@link #setDisplayMode} (the desktop mode before), like LWJGL 2: resizing the window does not
     * change it, {@link #getWidth()}/{@link #getHeight()} are the live size. {@code Minecraft.toggleFullscreen} reads it
     * right after setting the desktop mode and needs exactly that mode, whatever size the window was clamped to meanwhile.
     */
    public static DisplayMode getDisplayMode() {
        return currentMode != null ? currentMode : getDesktopDisplayMode();
    }

    public static void setDisplayMode(DisplayMode mode) throws LWJGLException {
        if (mode == null) {
            throw new IllegalArgumentException("mode must be non-null");
        }
        currentMode = mode;
        if (created) {
            try {
                WindowManager.applyMode(mode.getWidth(), mode.getHeight());
            } catch (RuntimeException e) {
                throw new LWJGLException("Could not set the display mode " + mode + ": " + e, e);
            }
        }
    }

    public static void setDisplayConfiguration(float gamma, float brightness, float contrast) throws LWJGLException {
        // gamma ramps do not exist for a Vulkan swapchain
    }

    public static void setFullscreen(boolean value) throws LWJGLException {
        fullscreen = value;
        if (created) {
            DisplayMode mode = getDisplayMode();
            WindowManager.setFullscreen(value, mode.getWidth(), mode.getHeight());
        }
    }

    public static void setDisplayModeAndFullscreen(DisplayMode mode) throws LWJGLException {
        if (mode == null) {
            throw new IllegalArgumentException("mode must be non-null");
        }
        currentMode = mode;
        fullscreen = true;
        if (created) {
            WindowManager.setFullscreen(true, mode.getWidth(), mode.getHeight());
        }
    }

    public static boolean isFullscreen() {
        return fullscreen;
    }

    // ---------------------------------------------------------------------------------------------------------
    // window properties
    // ---------------------------------------------------------------------------------------------------------

    public static void setTitle(String title) {
        WindowManager.setTitle(title);
    }

    public static String getTitle() {
        return WindowManager.getTitle();
    }

    /** Always null: embedding the display in an AWT canvas is not supported. */
    public static java.awt.Canvas getParent() {
        return null;
    }

    public static void setParent(java.awt.Canvas parent) throws LWJGLException {
        if (parent != null) {
            throw new LWJGLException("Display.setParent(Canvas) is not supported by the Vulkan display");
        }
    }

    /** RGBA pixels of square icons; returns the number of icons used (0 on macOS, GLFW has no window icon there). */
    public static int setIcon(ByteBuffer[] icons) {
        return WindowManager.setIcons(icons);
    }

    public static void setResizable(boolean resizable) {
        WindowManager.setResizable(resizable);
    }

    public static boolean isResizable() {
        return WindowManager.isResizable();
    }

    public static void setLocation(int x, int y) {
        WindowManager.setLocation(x, y);
    }

    public static int getX() {
        return WindowManager.getX();
    }

    public static int getY() {
        return WindowManager.getY();
    }

    /** Framebuffer width in pixels. */
    public static int getWidth() {
        if (created && WindowManager.getFramebufferWidth() > 0) {
            return WindowManager.getFramebufferWidth();
        }
        return getDisplayMode().getWidth();
    }

    /** Framebuffer height in pixels. */
    public static int getHeight() {
        if (created && WindowManager.getFramebufferHeight() > 0) {
            return WindowManager.getFramebufferHeight();
        }
        return getDisplayMode().getHeight();
    }

    /** Framebuffer pixels per screen coordinate (2 on a Retina display with a full resolution framebuffer, else 1). */
    public static float getPixelScaleFactor() {
        return (float) Math.max(WindowManager.getScaleX(), WindowManager.getScaleY());
    }

    public static String getAdapter() {
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

    public static String getVersion() {
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
    // state
    // ---------------------------------------------------------------------------------------------------------

    public static boolean isCreated() {
        return created;
    }

    /** True once per close request (window close button, Cmd+Q): a mod can veto it by not shutting down. */
    public static boolean isCloseRequested() {
        return created && WindowManager.consumeCloseRequest();
    }

    /** True while the window is not minimised. */
    public static boolean isVisible() {
        return created && !WindowManager.isIconified();
    }

    /** True while the window has the input focus (and is not minimised). */
    public static boolean isActive() {
        return created && WindowManager.isFocused() && !WindowManager.isIconified();
    }

    public static boolean isDirty() {
        return false;
    }

    /** True once after the framebuffer changed size. */
    public static boolean wasResized() {
        return created && WindowManager.consumeResized();
    }

    // ---------------------------------------------------------------------------------------------------------
    // create / destroy
    // ---------------------------------------------------------------------------------------------------------

    public static void create() throws LWJGLException {
        create(new PixelFormat());
    }

    public static void create(PixelFormat pixelFormat) throws LWJGLException {
        createImpl(pixelFormat, null);
    }

    public static void create(PixelFormat pixelFormat, Drawable sharedDrawable) throws LWJGLException {
        createImpl(pixelFormat, null);
    }

    public static void create(PixelFormat pixelFormat, ContextAttribs attribs) throws LWJGLException {
        createImpl(pixelFormat, attribs);
    }

    public static void create(PixelFormat pixelFormat, Drawable sharedDrawable, ContextAttribs attribs) throws LWJGLException {
        createImpl(pixelFormat, attribs);
    }

    public static void create(PixelFormatLWJGL pixelFormat) throws LWJGLException {
        createImpl(pixelFormat, null);
    }

    public static void create(PixelFormatLWJGL pixelFormat, Drawable sharedDrawable) throws LWJGLException {
        createImpl(pixelFormat, null);
    }

    private static void createImpl(PixelFormatLWJGL pixelFormat, ContextAttribs attribs) throws LWJGLException {
        if (created) {
            throw new IllegalStateException("Only one LWJGL context may be instantiated at any one time.");
        }
        VkLog.LOGGER.debug("Display.create({}, {})", pixelFormat, attribs);
        try {
            WindowManager.initGLFW();
            if (currentMode == null && !fullscreen) {
                currentMode = DEFAULT_WINDOW_MODE; // LWJGL 2 would create a desktop sized window: a game size is more useful
            }
            DisplayMode mode = getDisplayMode();
            int width = mode.getWidth() > 0 ? mode.getWidth() : DEFAULT_WINDOW_MODE.getWidth();
            int height = mode.getHeight() > 0 ? mode.getHeight() : DEFAULT_WINDOW_MODE.getHeight();
            WindowManager.createWindow(width, height, fullscreen);
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

    public static void destroy() {
        if (!created) {
            return;
        }
        created = false;
        tearDown();
        currentMode = null;
        fullscreen = false;
        VkLog.LOGGER.info("Display destroyed");
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

    // ---------------------------------------------------------------------------------------------------------
    // frame
    // ---------------------------------------------------------------------------------------------------------

    /** Presents the frame and processes the window events. */
    public static void update() {
        update(true);
    }

    public static void update(boolean processMessages) {
        if (!created) {
            return;
        }
        RenderCore.present();
        if (processMessages) {
            processMessages();
        }
    }

    /** Polls the window events (the keyboard and mouse queues are filled by the GLFW callbacks). */
    public static void processMessages() {
        if (created) {
            WindowManager.pollEvents();
        }
    }

    /** Presents the frame. */
    public static void swapBuffers() throws LWJGLException {
        if (created) {
            RenderCore.present();
        }
    }

    public static void setSwapInterval(int interval) {
        setVSyncEnabled(interval > 0);
    }

    /** Before {@link #create} the wish is remembered and applied right after the backend started. */
    public static void setVSyncEnabled(boolean sync) {
        vsyncRequested = true;
        vsyncEnabled = sync;
        if (created && VulkanBackend.isRunning()) {
            RenderCore.setVSync(sync);
        }
    }

    public static void setInitialBackground(float red, float green, float blue) {
        // the window is shown once the swapchain exists, there is no native clear colour
    }

    /** Frame limiter: sleeps most of the frame in 1 ms slices and spins the rest, the schedule does not drift (see {@link FrameLimiter}). */
    public static void sync(int fps) {
        FrameLimiter.sync(fps);
    }

    // ---------------------------------------------------------------------------------------------------------
    // context (nothing to make current: the backend is bound to the render thread)
    // ---------------------------------------------------------------------------------------------------------

    public static void makeCurrent() throws LWJGLException {
    }

    public static void releaseContext() throws LWJGLException {
    }

    public static boolean isCurrent() throws LWJGLException {
        return created;
    }

    private static final class DisplayDrawable implements Drawable {
        @Override
        public boolean isCurrent() throws LWJGLException {
            return created;
        }

        @Override
        public void makeCurrent() throws LWJGLException {
        }

        @Override
        public void releaseContext() throws LWJGLException {
        }

        @Override
        public void destroy() {
            Display.destroy();
        }

        @Override
        public void setCLSharingProperties(PointerBuffer properties) throws LWJGLException {
            throw new LWJGLException("OpenCL sharing is not supported");
        }
    }
}
