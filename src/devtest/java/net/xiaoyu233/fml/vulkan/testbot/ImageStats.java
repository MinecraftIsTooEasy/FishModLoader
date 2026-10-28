package net.xiaoyu233.fml.vulkan.testbot;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Small helpers to judge captured PNGs without looking at them (only used for checks the report can state as numbers). */
public final class ImageStats {
    private ImageStats() {
    }

    public static BufferedImage read(String path) throws Exception {
        BufferedImage img = ImageIO.read(new File(path));
        if (img == null) {
            throw new IllegalStateException("not a readable image: " + path);
        }
        return img;
    }

    /** Mean absolute difference per channel (0..255) over the rectangle (fractions of the image size) of two same sized images. */
    public static double meanAbsDiff(BufferedImage a, BufferedImage b, double fx, double fy, double fw, double fh) {
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
    public static double fractionNear(BufferedImage a, int rgb, int tol, double fx, double fy, double fw, double fh) {
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
    public static double meanLuma(BufferedImage a) {
        long sum = 0;
        for (int y = 0; y < a.getHeight(); y += 2) {
            for (int x = 0; x < a.getWidth(); x += 2) {
                int p = a.getRGB(x, y);
                sum += (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000;
            }
        }
        return (double) sum / (((a.getHeight() + 1) / 2) * ((a.getWidth() + 1) / 2));
    }

    // ------------------------------------------------------------------ comparison tooling (7.2): tolerance classes, histogram, SSIM, diff image

    /** Tolerance class of a capture scene (7.2): T0 exact, T1 lit/fogged 3D, T2 T1 outside a mask of the D10 effects. */
    public enum Tolerance {
        T0, T1, T2;

        public static Tolerance parse(String s) {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** Result of {@link #compare}: all numbers are over the gated pixels (outside the mask), the masked pixel count is reported separately. */
    public record Comparison(Tolerance tolerance, boolean sameSize, int width, int height, long gatedPixels, long maskedPixels, int maxDiff, long[] histogram,
                             double fractionWithin3, double ssim, double meanAbsDiff, boolean pass, String verdict) {
        /** One-line summary for reports. */
        public String summary() {
            if (!sameSize) {
                return verdict;
            }
            return String.format(java.util.Locale.ROOT, "%s %s: max diff %d, within 3: %.4f%%, SSIM %.5f, mean abs %.4f, masked px %d - %s", tolerance, pass ? "PASS" : "FAIL", maxDiff,
                fractionWithin3 * 100.0, ssim, meanAbsDiff, maskedPixels, verdict);
        }

        /** The histogram of the per-pixel max channel difference as a compact "0:n 1:n ..." string (non-empty buckets only; bucket 255 includes everything above 254). */
        public String histogramText() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < histogram.length; i++) {
                if (histogram[i] != 0) {
                    sb.append(sb.length() == 0 ? "" : " ").append(i).append(':').append(histogram[i]);
                }
            }
            return sb.toString();
        }
    }

    /** Per-pixel maximum channel difference (0..255) over RGB; {@code mask} (nullable, same size, any non-black pixel = masked) pixels are skipped. */
    public static long[] maxChannelDiffHistogram(BufferedImage a, BufferedImage b, BufferedImage mask) {
        long[] hist = new long[256];
        int w = a.getWidth();
        int h = a.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (isMasked(mask, x, y)) {
                    continue;
                }
                hist[pixelDiff(a.getRGB(x, y), b.getRGB(x, y))]++;
            }
        }
        return hist;
    }

    private static boolean isMasked(BufferedImage mask, int x, int y) {
        return mask != null && x < mask.getWidth() && y < mask.getHeight() && (mask.getRGB(x, y) & 0xFFFFFF) != 0;
    }

    private static int pixelDiff(int p, int q) {
        int dr = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255));
        int dg = Math.abs(((p >> 8) & 255) - ((q >> 8) & 255));
        int db = Math.abs((p & 255) - (q & 255));
        return Math.max(dr, Math.max(dg, db));
    }

    /** Rec. 601 luma 0..255 as a float plane (masked pixels are copied from {@code other} so they never contribute a difference). */
    private static float[] lumaPlane(BufferedImage img, BufferedImage mask, float[] other) {
        int w = img.getWidth();
        int h = img.getHeight();
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (other != null && isMasked(mask, x, y)) {
                    out[i] = other[i];
                    continue;
                }
                int p = img.getRGB(x, y);
                out[i] = 0.299F * ((p >> 16) & 255) + 0.587F * ((p >> 8) & 255) + 0.114F * (p & 255);
            }
        }
        return out;
    }

    /**
     * Mean structural similarity of the luma planes (8x8 windows, stride 4, C1 = (0.01 L)^2, C2 = (0.03 L)^2, L = 255; windows fully inside the mask are
     * skipped, partially masked windows use the other image's luma for the masked pixels so the mask never lowers the score). Identical images give 1.0.
     */
    public static double ssimLuma(BufferedImage a, BufferedImage b, BufferedImage mask) {
        int w = a.getWidth();
        int h = a.getHeight();
        float[] la = lumaPlane(a, null, null);
        float[] lb = lumaPlane(b, mask, la);
        if (mask != null) {
            la = lumaPlane(a, mask, lb);
        }
        final int win = 8;
        final int stride = 4;
        final double c1 = 6.5025;
        final double c2 = 58.5225;
        if (w < win || h < win) {
            return la.length == 0 || java.util.Arrays.equals(la, lb) ? 1.0 : 0.0;
        }
        double sum = 0;
        long n = 0;
        for (int y = 0; y + win <= h; y += stride) {
            for (int x = 0; x + win <= w; x += stride) {
                double ma = 0;
                double mb = 0;
                for (int j = 0; j < win; j++) {
                    int row = (y + j) * w + x;
                    for (int i = 0; i < win; i++) {
                        ma += la[row + i];
                        mb += lb[row + i];
                    }
                }
                ma /= win * win;
                mb /= win * win;
                double va = 0;
                double vb = 0;
                double cov = 0;
                for (int j = 0; j < win; j++) {
                    int row = (y + j) * w + x;
                    for (int i = 0; i < win; i++) {
                        double da = la[row + i] - ma;
                        double db = lb[row + i] - mb;
                        va += da * da;
                        vb += db * db;
                        cov += da * db;
                    }
                }
                double d = win * win - 1;
                va /= d;
                vb /= d;
                cov /= d;
                sum += ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2));
                n++;
            }
        }
        return n == 0 ? 1.0 : sum / n;
    }

    /**
     * Applies the tolerance class (7.2): T0 = pixel diff 0 everywhere; T1 = max per-channel difference at most 3 on at least 99.9 % of the pixels and luma SSIM at
     * least 0.999; T2 = T1 over the pixels outside {@code mask} (without a mask it degrades to T1 and says so). The masked area is counted, never gated.
     */
    public static Comparison compare(BufferedImage a, BufferedImage b, Tolerance tol, BufferedImage mask) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return new Comparison(tol, false, a.getWidth(), a.getHeight(), 0, 0, 255, new long[256], 0, 0, Double.NaN, false,
                "size mismatch " + a.getWidth() + "x" + a.getHeight() + " vs " + b.getWidth() + "x" + b.getHeight());
        }
        BufferedImage m = tol == Tolerance.T2 ? mask : null;
        long[] hist = maxChannelDiffHistogram(a, b, m);
        long gated = 0;
        long within3 = 0;
        long weighted = 0;
        int max = 0;
        for (int i = 0; i < hist.length; i++) {
            gated += hist[i];
            if (i <= 3) {
                within3 += hist[i];
            }
            weighted += hist[i] * i;
            if (hist[i] != 0) {
                max = i;
            }
        }
        long total = (long) a.getWidth() * a.getHeight();
        long masked = total - gated;
        double frac = gated == 0 ? 1.0 : (double) within3 / gated;
        double mean = gated == 0 ? 0.0 : (double) weighted / gated;
        boolean pass;
        String verdict;
        double ssim = 1.0;
        if (tol == Tolerance.T0) {
            pass = max == 0;
            verdict = pass ? "identical" : "not identical";
            if (!pass) {
                ssim = ssimLuma(a, b, null);
            }
        } else {
            ssim = ssimLuma(a, b, m);
            pass = frac >= 0.999 && ssim >= 0.999;
            verdict = pass ? "within tolerance" : (frac < 0.999 ? "too many pixels differ by more than 3" : "SSIM below 0.999");
            if (tol == Tolerance.T2 && mask == null) {
                verdict += " (no mask supplied: gated as T1)";
            }
        }
        return new Comparison(tol, true, a.getWidth(), a.getHeight(), gated, masked, max, hist, frac, ssim, mean, pass, verdict);
    }

    /** Diff image: black where equal, amplified (x16, min 64 for any difference) red/green/blue channel differences elsewhere; masked pixels dark blue. */
    public static BufferedImage diffImage(BufferedImage a, BufferedImage b, BufferedImage mask) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (isMasked(mask, x, y)) {
                    out.setRGB(x, y, 0x000040);
                    continue;
                }
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                int r = amplify(Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)));
                int g = amplify(Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)));
                int bl = amplify(Math.abs((p & 255) - (q & 255)));
                out.setRGB(x, y, (r << 16) | (g << 8) | bl);
            }
        }
        return out;
    }

    private static int amplify(int d) {
        return d == 0 ? 0 : Math.min(255, Math.max(64, d * 16));
    }
}
