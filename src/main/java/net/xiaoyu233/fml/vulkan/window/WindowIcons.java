package net.xiaoyu233.fml.vulkan.window;

import net.xiaoyu233.fml.vulkan.VkLog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Window icon set for GLFW. Reads icons/icon_16x16.png and icon_32x32.png from the assets dir (the legacy assets dir of the
 * launcher, as vanilla does); when they are missing or unreadable, the copies bundled in the jar are used, so the window never
 * ends up without a logo. Windows takes the taskbar / Alt-Tab icon from the larger sizes, therefore 48, 64, 128 and 256 px
 * versions (nearest-neighbour upscales of the 32 px icon, which keeps the pixel art sharp) are added.
 */
public final class WindowIcons {
    private static final int[] BASE = {16, 32};
    private static final int[] EXTRA = {48, 64, 128, 256};

    private WindowIcons() {
    }

    /** RGBA, direct, position 0, limit = size*size*4; ascending sizes. Never null, never empty (unless ImageIO is unusable). */
    public static ByteBuffer[] load(File assetsDir) {
        List<BufferedImage> images = new ArrayList<>();
        for (int size : BASE) {
            BufferedImage img = null;
            String name = "icon_" + size + "x" + size + ".png";
            if (assetsDir != null) {
                File f = new File(new File(assetsDir, "icons"), name);
                try {
                    img = ImageIO.read(f);
                    if (img == null) {
                        VkLog.LOGGER.warn("Window icon " + f + " is not a readable image");
                    }
                } catch (Throwable t) {
                    VkLog.LOGGER.warn("Could not read the window icon " + f + ": " + t);
                }
            }
            if (img == null) {
                try (InputStream in = WindowIcons.class.getResourceAsStream("/net/xiaoyu233/fml/vulkan/icons/" + name)) {
                    if (in != null) {
                        img = ImageIO.read(in);
                        VkLog.LOGGER.info("Using the bundled window icon " + name);
                    }
                } catch (Throwable t) {
                    VkLog.LOGGER.warn("Could not read the bundled window icon " + name + ": " + t);
                }
            }
            if (img != null) {
                images.add(img);
            }
        }
        List<ByteBuffer> out = new ArrayList<>();
        BufferedImage largest = null;
        for (BufferedImage img : images) {
            if (img.getWidth() == img.getHeight()) {
                out.add(toRgba(img, img.getWidth()));
                if (largest == null || img.getWidth() > largest.getWidth()) {
                    largest = img;
                }
            }
        }
        if (largest != null) {
            for (int size : EXTRA) {
                if (size > largest.getWidth()) {
                    out.add(toRgba(largest, size));
                }
            }
        }
        return out.toArray(new ByteBuffer[0]);
    }

    private static ByteBuffer toRgba(BufferedImage src, int size) {
        ByteBuffer buf = ByteBuffer.allocateDirect(size * size * 4);
        int sw = src.getWidth();
        for (int y = 0; y < size; y++) {
            int sy = y * src.getHeight() / size;
            for (int x = 0; x < size; x++) {
                int argb = src.getRGB(x * sw / size, sy);
                buf.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
        }
        buf.flip();
        return buf;
    }
}
