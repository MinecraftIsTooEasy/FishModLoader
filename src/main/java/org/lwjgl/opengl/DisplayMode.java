package org.lwjgl.opengl;

/**
 * LWJGL 2 API: a display mode (identical to LWJGL 2.9.0). Owned by module E. The public constructor makes a mode that is
 * not fullscreen capable with bpp and frequency 0; the modes of {@link Display#getAvailableDisplayModes()} come from GLFW
 * and report 32 bpp (24/32 bit colour) and the monitor refresh rate.
 */
public final class DisplayMode {
    private final int width;
    private final int height;
    private final int bpp;
    private final int freq;
    private final boolean fullscreen;

    public DisplayMode(int width, int height) {
        this(width, height, 0, 0, false);
    }

    DisplayMode(int width, int height, int bpp, int freq) {
        this(width, height, bpp, freq, true);
    }

    private DisplayMode(int width, int height, int bpp, int freq, boolean fullscreen) {
        this.width = width;
        this.height = height;
        this.bpp = bpp;
        this.freq = freq;
        this.fullscreen = fullscreen;
    }

    public boolean isFullscreenCapable() {
        return fullscreen;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getBitsPerPixel() {
        return bpp;
    }

    public int getFrequency() {
        return freq;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DisplayMode dm)) {
            return false;
        }
        return dm.width == width && dm.height == height && dm.bpp == bpp && dm.freq == freq;
    }

    @Override
    public int hashCode() {
        return width ^ height ^ freq ^ bpp;
    }

    @Override
    public String toString() {
        return width + " x " + height + " x " + bpp + " @" + freq + "Hz";
    }
}
