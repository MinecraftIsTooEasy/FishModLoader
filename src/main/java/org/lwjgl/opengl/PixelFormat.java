package org.lwjgl.opengl;

/**
 * LWJGL 2 API: the pixel format wish passed to {@code Display.create} (identical to LWJGL 2.9.0, including the argument
 * order, defaults and validation). Owned by module E. The Vulkan backend chooses its own surface and depth formats, the
 * values are only kept (and logged in debug mode).
 */
public final class PixelFormat implements PixelFormatLWJGL {
    private int bpp;
    private int alpha;
    private int depth;
    private int stencil;
    private int samples;
    private int colorSamples;
    private int numAuxBuffers;
    private int accumBpp;
    private int accumAlpha;
    private boolean stereo;
    private boolean floatingPoint;
    private boolean floatingPointPacked;
    private boolean sRGB;

    public PixelFormat() {
        this(0, 8, 0);
    }

    public PixelFormat(int alpha, int depth, int stencil) {
        this(alpha, depth, stencil, 0);
    }

    public PixelFormat(int alpha, int depth, int stencil, int samples) {
        this(0, alpha, depth, stencil, samples);
    }

    public PixelFormat(int bpp, int alpha, int depth, int stencil, int samples) {
        this(bpp, alpha, depth, stencil, samples, 0, 0, 0, false);
    }

    public PixelFormat(int bpp, int alpha, int depth, int stencil, int samples, int numAuxBuffers, int accumBpp, int accumAlpha, boolean stereo) {
        this(bpp, alpha, depth, stencil, samples, numAuxBuffers, accumBpp, accumAlpha, stereo, false);
    }

    public PixelFormat(int bpp, int alpha, int depth, int stencil, int samples, int numAuxBuffers, int accumBpp, int accumAlpha,
                       boolean stereo, boolean floatingPoint) {
        this.bpp = bpp;
        this.alpha = alpha;
        this.depth = depth;
        this.stencil = stencil;
        this.samples = samples;
        this.numAuxBuffers = numAuxBuffers;
        this.accumBpp = accumBpp;
        this.accumAlpha = accumAlpha;
        this.stereo = stereo;
        this.floatingPoint = floatingPoint;
        this.floatingPointPacked = false;
        this.sRGB = false;
    }

    private PixelFormat(PixelFormat pf) {
        this.bpp = pf.bpp;
        this.alpha = pf.alpha;
        this.depth = pf.depth;
        this.stencil = pf.stencil;
        this.samples = pf.samples;
        this.colorSamples = pf.colorSamples;
        this.numAuxBuffers = pf.numAuxBuffers;
        this.accumBpp = pf.accumBpp;
        this.accumAlpha = pf.accumAlpha;
        this.stereo = pf.stereo;
        this.floatingPoint = pf.floatingPoint;
        this.floatingPointPacked = pf.floatingPointPacked;
        this.sRGB = pf.sRGB;
    }

    public int getBitsPerPixel() {
        return bpp;
    }

    public PixelFormat withBitsPerPixel(int bpp) {
        if (bpp < 0) {
            throw new IllegalArgumentException("Invalid number of bits per pixel specified: " + bpp);
        }
        PixelFormat pf = new PixelFormat(this);
        pf.bpp = bpp;
        return pf;
    }

    public int getAlphaBits() {
        return alpha;
    }

    public PixelFormat withAlphaBits(int alpha) {
        if (alpha < 0) {
            throw new IllegalArgumentException("Invalid number of alpha bits specified: " + alpha);
        }
        PixelFormat pf = new PixelFormat(this);
        pf.alpha = alpha;
        return pf;
    }

    public int getDepthBits() {
        return depth;
    }

    public PixelFormat withDepthBits(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("Invalid number of depth bits specified: " + depth);
        }
        PixelFormat pf = new PixelFormat(this);
        pf.depth = depth;
        return pf;
    }

    public int getStencilBits() {
        return stencil;
    }

    public PixelFormat withStencilBits(int stencil) {
        if (stencil < 0) {
            throw new IllegalArgumentException("Invalid number of stencil bits specified: " + stencil);
        }
        PixelFormat pf = new PixelFormat(this);
        pf.stencil = stencil;
        return pf;
    }

    public int getSamples() {
        return samples;
    }

    public PixelFormat withSamples(int samples) {
        if (samples < 0) {
            throw new IllegalArgumentException("Invalid number of samples specified: " + samples);
        }
        PixelFormat pf = new PixelFormat(this);
        pf.samples = samples;
        return pf;
    }

    public PixelFormat withCoverageSamples(int colorSamples) {
        return withCoverageSamples(colorSamples, samples);
    }

    public PixelFormat withCoverageSamples(int colorSamples, int coverageSamples) {
        if (coverageSamples < 0 || colorSamples < 0 || (coverageSamples == 0 && 0 < colorSamples) || coverageSamples < colorSamples) {
            throw new IllegalArgumentException("Invalid number of coverage samples specified: " + coverageSamples + " - " + colorSamples);
        }
        PixelFormat pf = new PixelFormat(this);
        pf.samples = coverageSamples;
        pf.colorSamples = colorSamples;
        return pf;
    }

    public int getAuxBuffers() {
        return numAuxBuffers;
    }

    public PixelFormat withAuxBuffers(int numAuxBuffers) {
        if (numAuxBuffers < 0) {
            throw new IllegalArgumentException("Invalid number of auxiliary buffers specified: " + numAuxBuffers);
        }
        PixelFormat pf = new PixelFormat(this);
        pf.numAuxBuffers = numAuxBuffers;
        return pf;
    }

    public int getAccumulationBitsPerPixel() {
        return accumBpp;
    }

    public PixelFormat withAccumulationBitsPerPixel(int accumBpp) {
        if (accumBpp < 0) {
            throw new IllegalArgumentException("Invalid number of bits per pixel in the accumulation buffer specified: " + accumBpp);
        }
        PixelFormat pf = new PixelFormat(this);
        pf.accumBpp = accumBpp;
        return pf;
    }

    public int getAccumulationAlpha() {
        return accumAlpha;
    }

    public PixelFormat withAccumulationAlpha(int accumAlpha) {
        if (accumAlpha < 0) {
            throw new IllegalArgumentException("Invalid number of alpha bits in the accumulation buffer specified: " + accumAlpha);
        }
        PixelFormat pf = new PixelFormat(this);
        pf.accumAlpha = accumAlpha;
        return pf;
    }

    public boolean isStereo() {
        return stereo;
    }

    public PixelFormat withStereo(boolean stereo) {
        PixelFormat pf = new PixelFormat(this);
        pf.stereo = stereo;
        return pf;
    }

    public boolean isFloatingPoint() {
        return floatingPoint;
    }

    public PixelFormat withFloatingPoint(boolean floatingPoint) {
        PixelFormat pf = new PixelFormat(this);
        pf.floatingPoint = floatingPoint;
        if (floatingPoint) {
            pf.floatingPointPacked = false;
        }
        return pf;
    }

    public PixelFormat withFloatingPointPacked(boolean floatingPointPacked) {
        PixelFormat pf = new PixelFormat(this);
        pf.floatingPointPacked = floatingPointPacked;
        if (floatingPointPacked) {
            pf.floatingPoint = false;
        }
        return pf;
    }

    public boolean isSRGB() {
        return sRGB;
    }

    public PixelFormat withSRGB(boolean sRGB) {
        PixelFormat pf = new PixelFormat(this);
        pf.sRGB = sRGB;
        return pf;
    }

    @Override
    public String toString() {
        return "PixelFormat{bpp=" + bpp + ", alpha=" + alpha + ", depth=" + depth + ", stencil=" + stencil + ", samples=" + samples
                + ", aux=" + numAuxBuffers + ", accum=" + accumBpp + "/" + accumAlpha + ", stereo=" + stereo
                + ", float=" + floatingPoint + ", packedFloat=" + floatingPointPacked + ", sRGB=" + sRGB + '}';
    }
}
