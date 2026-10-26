package net.xiaoyu233.fml.vulkan.testbot;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Small helpers to judge captured PNGs without looking at them (only used for checks the report can state as numbers). */
final class ImageStats {
    private ImageStats() {
    }

    static BufferedImage read(String path) throws Exception {
        BufferedImage img = ImageIO.read(new File(path));
        if (img == null) {
            throw new IllegalStateException("not a readable image: " + path);
        }
        return img;
    }

    /** Mean absolute difference per channel (0..255) over the rectangle (fractions of the image size) of two same sized images. */
    static double meanAbsDiff(BufferedImage a, BufferedImage b, double fx, double fy, double fw, double fh) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return Double.NaN;
        }
        int x0 = (int) (fx * a.getWidth());
        int y0 = (int) (fy * a.getHeight());
        int x1 = Math.min(a.getWidth(), x0 + Math.max(1, (int) (fw * a.getWidth())));
        int y1 = Math.min(a.getHeight(), y0 + Math.max(1, (int) (fh * a.getHeight())));
        long sum = 0;
        long n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                sum += Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) + Math.abs((p & 255) - (q & 255));
                n += 3;
            }
        }
        return (double) sum / n;
    }

    /** Fraction of pixels (in the rectangle) that are within {@code tol} of the colour. */
    static double fractionNear(BufferedImage a, int rgb, int tol, double fx, double fy, double fw, double fh) {
        int x0 = (int) (fx * a.getWidth());
        int y0 = (int) (fy * a.getHeight());
        int x1 = Math.min(a.getWidth(), x0 + Math.max(1, (int) (fw * a.getWidth())));
        int y1 = Math.min(a.getHeight(), y0 + Math.max(1, (int) (fh * a.getHeight())));
        long hit = 0;
        long n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = a.getRGB(x, y);
                if (Math.abs(((p >> 16) & 255) - ((rgb >> 16) & 255)) <= tol && Math.abs(((p >> 8) & 255) - ((rgb >> 8) & 255)) <= tol
                    && Math.abs((p & 255) - (rgb & 255)) <= tol) {
                    hit++;
                }
                n++;
            }
        }
        return (double) hit / n;
    }

    /** Mean luminance 0..255 over the whole image (a black / empty frame is near 0). */
    static double meanLuma(BufferedImage a) {
        long sum = 0;
        for (int y = 0; y < a.getHeight(); y += 2) {
            for (int x = 0; x < a.getWidth(); x += 2) {
                int p = a.getRGB(x, y);
                sum += (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000;
            }
        }
        return (double) sum / (((a.getHeight() + 1) / 2) * ((a.getWidth() + 1) / 2));
    }
}
