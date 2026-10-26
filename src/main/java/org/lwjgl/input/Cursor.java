package org.lwjgl.input;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.window.WindowManager;
import org.lwjgl.LWJGLException;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.glfwCreateCursor;
import static org.lwjgl.glfw.GLFW.glfwDestroyCursor;

/**
 * LWJGL 2 API: a native mouse cursor (public API of LWJGL 2.9.0). Owned by module E. The cursor is a GLFW cursor made from
 * the first image (ARGB ints, origin bottom-left like LWJGL 2); animated cursors are shown as their first frame. Create it
 * and use it on the render thread.
 */
public class Cursor {
    public static final int CURSOR_ONE_BIT_TRANSPARENCY = 1;
    public static final int CURSOR_8_BIT_ALPHA = 2;
    public static final int CURSOR_ANIMATION = 4;

    private static final int MIN_SIZE = 16;
    private static final int MAX_SIZE = 32;

    private long handle;
    private final int generation;

    /**
     * @param width      width of the image(s)
     * @param height     height of the image(s)
     * @param xHotspot   x of the hotspot (from the left)
     * @param yHotspot   y of the hotspot (from the bottom)
     * @param numImages  number of images (frames of an animation)
     * @param images     the images, ARGB, {@code width * height * numImages} ints from the position of the buffer
     * @param delays     frame delays of an animation (ignored)
     */
    public Cursor(int width, int height, int xHotspot, int yHotspot, int numImages, IntBuffer images, IntBuffer delays) throws LWJGLException {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("The cursor image size must be positive: " + width + "x" + height);
        }
        if (numImages < 1) {
            throw new IllegalArgumentException("A cursor needs at least one image");
        }
        if (images == null || images.remaining() < width * height * numImages) {
            throw new IllegalArgumentException("Not enough elements in the cursor image buffer: " + width * height * numImages + " needed");
        }
        WindowManager.initGLFW();
        generation = WindowManager.getGlfwGeneration();
        if (numImages > 1) {
            VkLog.LOGGER.debug("Animated cursors are not supported, using the first of {} frames", numImages);
        }
        // GLFW wants RGBA bytes, rows from the top; LWJGL 2 gives ARGB ints, rows from the bottom
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
        int base = images.position();
        for (int row = 0; row < height; row++) {
            int srcRow = height - 1 - row;
            for (int x = 0; x < width; x++) {
                int argb = images.get(base + srcRow * width + x);
                pixels.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >> 24));
            }
        }
        pixels.flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GLFWImage image = GLFWImage.malloc(stack).set(width, height, pixels);
            handle = glfwCreateCursor(image, xHotspot, height - 1 - yHotspot);
        }
        if (handle == 0L) {
            throw new LWJGLException("Failed to create the cursor");
        }
    }

    public static int getMinCursorSize() {
        return MIN_SIZE;
    }

    public static int getMaxCursorSize() {
        return MAX_SIZE;
    }

    public static int getCapabilities() {
        return CURSOR_8_BIT_ALPHA | CURSOR_ONE_BIT_TRANSPARENCY;
    }

    public void destroy() {
        if (handle != 0L && generation == WindowManager.getGlfwGeneration() && WindowManager.isGLFWReady()) {
            glfwDestroyCursor(handle);
        }
        handle = 0L;
    }

    /** The GLFW cursor, 0 when destroyed or when GLFW has been shut down since it was created. */
    public long getHandle() {
        return generation == WindowManager.getGlfwGeneration() && WindowManager.isGLFWReady() ? handle : 0L;
    }
}
