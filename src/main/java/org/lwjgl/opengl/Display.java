package org.lwjgl.opengl;

import net.xiaoyu233.fml.vulkan.render.frame.FramePacer;
import net.xiaoyu233.fml.vulkan.window.NativeWindow;
import net.xiaoyu233.fml.vulkan.window.WindowManager;
import org.lwjgl.LWJGLException;
import org.lwjgl.PointerBuffer;

import java.nio.ByteBuffer;

/**
 * LWJGL 2 API: the window of the game, on GLFW + the Vulkan backend (module E); a thin shim over {@link NativeWindow}, which holds the state. The public API is the one of LWJGL 2.9.0
 * ({@code Display.create(PixelFormatLWJGL, org.lwjgl.opengles.ContextAttribs)} excepted: there is no GLES).
 *
 * <p>Everything the game sees is in framebuffer pixels: {@link #getWidth()}/{@link #getHeight()}, the {@link DisplayMode}s
 * and what {@link #getDisplayMode()} returns after {@link #setDisplayMode}. A mode is the size of the framebuffer, on a
 * Retina display the window is half as large in screen coordinates. Render thread only.
 */
public final class Display {
    private static final Drawable DRAWABLE = new DisplayDrawable();

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
        return NativeWindow.desktopDisplayMode();
    }

    /**
     * The mode last set with {@link #setDisplayMode} (the desktop mode before), like LWJGL 2: resizing the window does not
     * change it, {@link #getWidth()}/{@link #getHeight()} are the live size.
     */
    public static DisplayMode getDisplayMode() {
        return NativeWindow.displayMode();
    }

    public static void setDisplayMode(DisplayMode mode) throws LWJGLException {
        NativeWindow.setDisplayMode(mode);
    }

    public static void setDisplayConfiguration(float gamma, float brightness, float contrast) throws LWJGLException {
        // gamma ramps do not exist for a Vulkan swapchain
    }

    /** Immediate (LWJGL 2 semantics); migrated game code uses {@code NativeWindow.requestFullscreen}, which switches after the present. */
    public static void setFullscreen(boolean value) throws LWJGLException {
        NativeWindow.setFullscreenNow(value);
    }

    public static void setDisplayModeAndFullscreen(DisplayMode mode) throws LWJGLException {
        NativeWindow.setDisplayModeAndFullscreen(mode);
    }

    public static boolean isFullscreen() {
        return NativeWindow.isFullscreen();
    }

    // ---------------------------------------------------------------------------------------------------------
    // window properties
    // ---------------------------------------------------------------------------------------------------------

    public static void setTitle(String title) {
        NativeWindow.setTitle(title);
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
        return NativeWindow.width();
    }

    /** Framebuffer height in pixels. */
    public static int getHeight() {
        return NativeWindow.height();
    }

    /** Framebuffer pixels per screen coordinate (2 on a Retina display with a full resolution framebuffer, else 1). */
    public static float getPixelScaleFactor() {
        return (float) Math.max(WindowManager.getScaleX(), WindowManager.getScaleY());
    }

    public static String getAdapter() {
        return NativeWindow.adapterName();
    }

    public static String getVersion() {
        return NativeWindow.driverVersion();
    }

    // ---------------------------------------------------------------------------------------------------------
    // state
    // ---------------------------------------------------------------------------------------------------------

    public static boolean isCreated() {
        return NativeWindow.isCreated();
    }

    /** True once per close request (window close button, Cmd+Q): a mod can veto it by not shutting down. */
    public static boolean isCloseRequested() {
        return NativeWindow.isCloseRequested();
    }

    /** True while the window is not minimised. */
    public static boolean isVisible() {
        return NativeWindow.isCreated() && !NativeWindow.isIconified();
    }

    /** True while the window has the input focus (and is not minimised). */
    public static boolean isActive() {
        return NativeWindow.isActive();
    }

    public static boolean isDirty() {
        return false;
    }

    /** True once after the framebuffer changed size. */
    public static boolean wasResized() {
        return NativeWindow.wasResized();
    }

    // ---------------------------------------------------------------------------------------------------------
    // create / destroy
    // ---------------------------------------------------------------------------------------------------------

    public static void create() throws LWJGLException {
        create(new PixelFormat());
    }

    public static void create(PixelFormat pixelFormat) throws LWJGLException {
        NativeWindow.createDisplay(pixelFormat, null);
    }

    public static void create(PixelFormat pixelFormat, Drawable sharedDrawable) throws LWJGLException {
        NativeWindow.createDisplay(pixelFormat, null);
    }

    public static void create(PixelFormat pixelFormat, ContextAttribs attribs) throws LWJGLException {
        NativeWindow.createDisplay(pixelFormat, attribs);
    }

    public static void create(PixelFormat pixelFormat, Drawable sharedDrawable, ContextAttribs attribs) throws LWJGLException {
        NativeWindow.createDisplay(pixelFormat, attribs);
    }

    public static void create(PixelFormatLWJGL pixelFormat) throws LWJGLException {
        NativeWindow.createDisplay(pixelFormat, null);
    }

    public static void create(PixelFormatLWJGL pixelFormat, Drawable sharedDrawable) throws LWJGLException {
        NativeWindow.createDisplay(pixelFormat, null);
    }

    public static void destroy() {
        NativeWindow.destroy();
    }

    // ---------------------------------------------------------------------------------------------------------
    // frame
    // ---------------------------------------------------------------------------------------------------------

    /** Presents the frame and processes the window events. */
    public static void update() {
        update(true);
    }

    public static void update(boolean processMessages) {
        if (!NativeWindow.isCreated()) {
            return;
        }
        if (processMessages) {
            NativeWindow.update();
        } else {
            NativeWindow.present();
        }
    }

    /** Polls the window events (the keyboard and mouse queues are filled by the GLFW callbacks). */
    public static void processMessages() {
        NativeWindow.pollEvents();
    }

    /** Presents the frame. */
    public static void swapBuffers() throws LWJGLException {
        NativeWindow.present();
    }

    public static void setSwapInterval(int interval) {
        setVSyncEnabled(interval > 0);
    }

    /** Before {@link #create} the wish is remembered and applied right after the backend started. */
    public static void setVSyncEnabled(boolean sync) {
        NativeWindow.setVSync(sync);
    }

    public static void setInitialBackground(float red, float green, float blue) {
        // the window is shown once the swapchain exists, there is no native clear colour
    }

    /** Frame limiter: sleeps most of the frame in 1 ms slices and spins the rest, the schedule does not drift (see {@code FrameLimiter}). */
    public static void sync(int fps) {
        FramePacer.limit(fps);
    }

    // ---------------------------------------------------------------------------------------------------------
    // context (nothing to make current: the backend is bound to the render thread)
    // ---------------------------------------------------------------------------------------------------------

    public static void makeCurrent() throws LWJGLException {
    }

    public static void releaseContext() throws LWJGLException {
    }

    public static boolean isCurrent() throws LWJGLException {
        return NativeWindow.isCreated();
    }

    private static final class DisplayDrawable implements Drawable {
        @Override
        public boolean isCurrent() throws LWJGLException {
            return NativeWindow.isCreated();
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
