package net.xiaoyu233.fml.vulkan.texture;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteOrder;

/**
 * GL pixel transfer conversion between client memory in a GL (format, type) layout and the 8 bit per channel storage of
 * textures (module B). Everything works on native addresses: callers pass {@code MemoryUtil.memAddress(buffer)} (which includes the
 * buffer position) and never copy through intermediate buffers. Memory safety is the caller's job: {@link #requiredBytes} must be
 * checked against the bytes really available before {@link #toBgra8} / {@link #fromRgba8} are called.
 *
 * <p>Texture storage is B8G8R8A8: the bytes of a texel in memory are B, G, R, A. The {@code UNSIGNED_INT_8_8_8_8_REV} packed
 * types hold their first component in the least significant bits of a host-order int, so BGRA/REV (what MITE uses for every
 * upload and for screenshots) is byte-for-byte the storage format on little-endian hosts and takes a memcpy path.
 */
final class PixelFormatConverter {
    private static final boolean LITTLE = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    // what a texture of a given internalformat stores (which channels are real, the others are derived like GL does)
    static final int CLASS_RGBA = 0;
    static final int CLASS_RGB = 1;
    static final int CLASS_LUMINANCE = 2;
    static final int CLASS_LUMINANCE_ALPHA = 3;
    static final int CLASS_ALPHA = 4;
    static final int CLASS_INTENSITY = 5;
    static final int CLASS_RED = 6;
    static final int CLASS_RG = 7;
    /** Depth / stencil internal formats: not representable by the colour-only storage. */
    static final int CLASS_UNSUPPORTED = -1;

    // known pixel types that are valid GL enums but not converted (reported as unsupported, not as GL_INVALID_ENUM)
    private static final int GL_UNSIGNED_BYTE_3_3_2 = 0x8032;
    private static final int GL_UNSIGNED_SHORT_4_4_4_4 = 0x8033;
    private static final int GL_UNSIGNED_SHORT_5_5_5_1 = 0x8034;
    private static final int GL_UNSIGNED_INT_10_10_10_2 = 0x8036;
    private static final int GL_UNSIGNED_BYTE_2_3_3_REV = 0x8362;
    private static final int GL_UNSIGNED_SHORT_5_6_5 = 0x8363;
    private static final int GL_UNSIGNED_SHORT_5_6_5_REV = 0x8364;
    private static final int GL_UNSIGNED_SHORT_4_4_4_4_REV = 0x8365;
    private static final int GL_UNSIGNED_SHORT_1_5_5_5_REV = 0x8366;
    private static final int GL_UNSIGNED_INT_2_10_10_10_REV = 0x8368;
    private static final int GL_UNSIGNED_INT_24_8 = 0x84FA;
    private static final int GL_FLOAT_32_UNSIGNED_INT_24_8_REV = 0x8DAD;
    private static final int GL_BITMAP = 0x1A00;

    private PixelFormatConverter() {
    }

    /** One direction (UNPACK or PACK) of the glPixelStore state. */
    static final class PixelStore {
        int alignment = 4;
        int rowLength;
        int skipRows;
        int skipPixels;
    }

    // ---------------------------------------------------------------------------------------------------------
    // format / type tables
    // ---------------------------------------------------------------------------------------------------------

    /** Components of a pixel transfer format, 0 when it is not a (colour or depth/stencil index) transfer format. */
    static int components(int format) {
        return switch (format) {
            case GL11.GL_RED, GL11.GL_GREEN, GL11.GL_BLUE, GL11.GL_ALPHA, GL11.GL_LUMINANCE,
                 GL11.GL_DEPTH_COMPONENT, GL11.GL_STENCIL_INDEX -> 1;
            case GL11.GL_LUMINANCE_ALPHA, GL30.GL_RG -> 2;
            case GL11.GL_RGB, GL12.GL_BGR -> 3;
            case GL11.GL_RGBA, GL12.GL_BGRA -> 4;
            default -> 0;
        };
    }

    static boolean isDepthOrStencil(int format) {
        return format == GL11.GL_DEPTH_COMPONENT || format == GL11.GL_STENCIL_INDEX || format == GL30.GL_DEPTH_STENCIL;
    }

    static boolean isPacked8888(int type) {
        return type == GL12.GL_UNSIGNED_INT_8_8_8_8 || type == GL12.GL_UNSIGNED_INT_8_8_8_8_REV;
    }

    /** Size in bytes of one component of a non-packed type, 0 otherwise. */
    static int typeSize(int type) {
        return switch (type) {
            case GL11.GL_UNSIGNED_BYTE, GL11.GL_BYTE -> 1;
            case GL11.GL_UNSIGNED_SHORT, GL11.GL_SHORT -> 2;
            case GL11.GL_UNSIGNED_INT, GL11.GL_INT, GL11.GL_FLOAT -> 4;
            default -> 0;
        };
    }

    /**
     * Bytes of one pixel in client memory: &gt; 0 for a supported (format, type) pair, 0 for a valid but not converted pair (reported as
     * unsupported), -1 when the pair is an invalid enum / invalid combination (GL_INVALID_ENUM or GL_INVALID_OPERATION).
     */
    static int bytesPerPixel(int format, int type) {
        int n = components(format);
        if (n == 0) {
            return format == GL30.GL_DEPTH_STENCIL ? 0 : -1;
        }
        switch (type) {
            case GL11.GL_UNSIGNED_BYTE:
            case GL11.GL_BYTE:
                return n;
            case GL11.GL_UNSIGNED_SHORT:
            case GL11.GL_SHORT:
                return 2 * n;
            case GL11.GL_UNSIGNED_INT:
            case GL11.GL_INT:
            case GL11.GL_FLOAT:
                return 4 * n;
            case GL12.GL_UNSIGNED_INT_8_8_8_8:
            case GL12.GL_UNSIGNED_INT_8_8_8_8_REV:
                return format == GL11.GL_RGBA || format == GL12.GL_BGRA ? 4 : -1;
            case GL30.GL_HALF_FLOAT:
            case GL_UNSIGNED_BYTE_3_3_2:
            case GL_UNSIGNED_BYTE_2_3_3_REV:
            case GL_UNSIGNED_SHORT_5_6_5:
            case GL_UNSIGNED_SHORT_5_6_5_REV:
            case GL_UNSIGNED_SHORT_4_4_4_4:
            case GL_UNSIGNED_SHORT_4_4_4_4_REV:
            case GL_UNSIGNED_SHORT_5_5_5_1:
            case GL_UNSIGNED_SHORT_1_5_5_5_REV:
            case GL_UNSIGNED_INT_10_10_10_2:
            case GL_UNSIGNED_INT_2_10_10_10_REV:
            case GL_UNSIGNED_INT_24_8:
            case GL_BITMAP:
                return 0;
            default:
                return -1;
        }
    }

    /**
     * Bytes of one pixel of a depth / stencil read-back (format DEPTH_COMPONENT, STENCIL_INDEX or DEPTH_STENCIL; see
     * {@link #isDepthOrStencil}), -1 for an invalid (format, type) pair. The data itself is never converted ({@code glReadPixels} reports
     * zeros), the size only tells how much client memory the transfer covers.
     */
    static int depthStencilBytesPerPixel(int format, int type) {
        if (format == GL30.GL_DEPTH_STENCIL) {
            return type == GL_UNSIGNED_INT_24_8 ? 4 : type == GL_FLOAT_32_UNSIGNED_INT_24_8_REV ? 8 : -1;
        }
        int size = typeSize(type);
        return size > 0 ? size : -1;
    }

    /** Which channels a texture with this internalformat stores; {@link #CLASS_UNSUPPORTED} for depth / stencil formats. */
    static int classifyInternalFormat(int f) {
        switch (f) {
            case 1:
                return CLASS_LUMINANCE;
            case 2:
                return CLASS_LUMINANCE_ALPHA;
            case 3:
                return CLASS_RGB;
            case 4:
                return CLASS_RGBA;
            case GL11.GL_ALPHA:
            case 0x84E9: // COMPRESSED_ALPHA
            case 0x8816: // ALPHA32F
            case 0x881C: // ALPHA16F
                return CLASS_ALPHA;
            case GL11.GL_LUMINANCE:
            case 0x84EA: // COMPRESSED_LUMINANCE
            case 0x8C46: // SLUMINANCE
            case 0x8C47: // SLUMINANCE8
            case 0x8818: // LUMINANCE32F
            case 0x881E: // LUMINANCE16F
                return CLASS_LUMINANCE;
            case GL11.GL_LUMINANCE_ALPHA:
            case 0x84EB: // COMPRESSED_LUMINANCE_ALPHA
            case 0x8C44: // SLUMINANCE_ALPHA
            case 0x8C45: // SLUMINANCE8_ALPHA8
            case 0x8819: // LUMINANCE_ALPHA32F
            case 0x881F: // LUMINANCE_ALPHA16F
                return CLASS_LUMINANCE_ALPHA;
            case GL11.GL_INTENSITY:
            case 0x84EC: // COMPRESSED_INTENSITY
            case 0x8817: // INTENSITY32F
            case 0x881D: // INTENSITY16F
                return CLASS_INTENSITY;
            case GL11.GL_RGB:
            case 0x2A10: // R3_G3_B2
            case 0x84ED: // COMPRESSED_RGB
            case 0x8C40: // SRGB
            case 0x8C41: // SRGB8
            case 0x8815: // RGB32F
            case 0x881B: // RGB16F
                return CLASS_RGB;
            case GL11.GL_RED:
            case 0x8229: // R8
            case 0x822A: // R16
            case 0x822D: // R16F
            case 0x822E: // R32F
                return CLASS_RED;
            case GL30.GL_RG:
            case 0x822B: // RG8
            case 0x822C: // RG16
            case 0x822F: // RG16F
            case 0x8230: // RG32F
                return CLASS_RG;
            case GL11.GL_DEPTH_COMPONENT:
            case GL11.GL_STENCIL_INDEX:
            case GL30.GL_DEPTH_STENCIL:
            case 0x81A5: // DEPTH_COMPONENT16
            case 0x81A6: // DEPTH_COMPONENT24
            case 0x81A7: // DEPTH_COMPONENT32
            case 0x88F0: // DEPTH24_STENCIL8
            case 0x8CAC: // DEPTH_COMPONENT32F
            case 0x8CAD: // DEPTH32F_STENCIL8
            case 0x8D46: // STENCIL_INDEX1
            case 0x8D47: // STENCIL_INDEX4
            case 0x8D48: // STENCIL_INDEX8
            case 0x8D49: // STENCIL_INDEX16
                return CLASS_UNSUPPORTED;
            default:
                break;
        }
        if (f >= 0x803B && f <= 0x803E) {
            return CLASS_ALPHA; // ALPHA4..ALPHA16
        }
        if (f >= 0x803F && f <= 0x8042) {
            return CLASS_LUMINANCE; // LUMINANCE4..LUMINANCE16
        }
        if (f >= 0x8043 && f <= 0x8048) {
            return CLASS_LUMINANCE_ALPHA;
        }
        if (f >= 0x8049 && f <= 0x804D) {
            return CLASS_INTENSITY;
        }
        if (f >= 0x804F && f <= 0x8054) {
            return CLASS_RGB; // RGB4 .. RGB16
        }
        return CLASS_RGBA; // RGBA, RGBA2..RGBA16, sRGB alpha, float/integer RGBA, compressed RGBA...
    }

    /** True when the texture class has a real alpha channel (alpha of the source is kept). */
    static boolean hasAlpha(int cls) {
        return cls == CLASS_RGBA || cls == CLASS_LUMINANCE_ALPHA || cls == CLASS_ALPHA || cls == CLASS_INTENSITY;
    }

    // ---------------------------------------------------------------------------------------------------------
    // client memory layout (GL 1.x pixel store rules)
    // ---------------------------------------------------------------------------------------------------------

    /** Distance in bytes between two rows of client memory (row length and alignment applied). */
    static long rowStride(int width, int bytesPerPixel, PixelStore ps) {
        long rowBytes = (long) (ps.rowLength > 0 ? ps.rowLength : width) * bytesPerPixel;
        int a = ps.alignment;
        return a > 1 ? (rowBytes + a - 1) / a * a : rowBytes;
    }

    /**
     * Byte offset of the first pixel (skip rows / skip pixels applied). Saturates to {@link Long#MAX_VALUE} when the offset does not fit
     * a long: no buffer is that large, so the callers' size check ({@link #requiredBytes}) rejects the transfer instead of wrapping around.
     */
    static long startOffset(int bytesPerPixel, long stride, PixelStore ps) {
        try {
            return Math.addExact(Math.multiplyExact((long) ps.skipRows, stride), (long) ps.skipPixels * bytesPerPixel);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Bytes of client memory that a transfer of width x height pixels touches, measured from the buffer start; {@link Long#MAX_VALUE}
     * when that does not fit a long. This is the memory safety check of every native read / write of client memory, so it must never wrap.
     */
    static long requiredBytes(int width, int height, int bytesPerPixel, PixelStore ps) {
        if (width <= 0 || height <= 0) {
            return 0;
        }
        long stride = rowStride(width, bytesPerPixel, ps);
        try {
            return Math.addExact(Math.addExact(startOffset(bytesPerPixel, stride, ps), Math.multiplyExact((long) (height - 1), stride)),
                (long) width * bytesPerPixel);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // client memory -> B8G8R8A8 (glTexImage2D / glTexSubImage2D)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Converts width x height pixels from client memory into tightly packed B8G8R8A8 ({@code dst}, width*height*4 bytes) and applies
     * the channel rules of the destination internalformat class (alpha forced to 1 for RGB, luminance/intensity replication...).
     *
     * @param srcRow0   address of the first pixel (skip rows / pixels already applied)
     * @param srcStride bytes between rows
     * @return false when the (format, type) pair is not supported (nothing written)
     */
    static boolean toBgra8(long srcRow0, long srcStride, int width, int height, int format, int type, int internalClass, long dst) {
        int bpp = bytesPerPixel(format, type);
        if (bpp <= 0 || isDepthOrStencil(format) || width <= 0 || height <= 0) {
            return false;
        }
        long pixels = (long) width * height;
        if (LITTLE && format == GL12.GL_BGRA && (type == GL11.GL_UNSIGNED_BYTE || type == GL12.GL_UNSIGNED_INT_8_8_8_8_REV)) {
            // fast path: the client layout is the storage layout
            long rowBytes = (long) width * 4;
            if (srcStride == rowBytes) {
                MemoryUtil.memCopy(srcRow0, dst, rowBytes * height);
            } else {
                for (int y = 0; y < height; y++) {
                    MemoryUtil.memCopy(srcRow0 + y * srcStride, dst + y * rowBytes, rowBytes);
                }
            }
        } else if (isPacked8888(type)) {
            decodePacked(srcRow0, srcStride, width, height, format, type == GL12.GL_UNSIGNED_INT_8_8_8_8_REV, dst);
        } else {
            decodeComponents(srcRow0, srcStride, width, height, format, type, dst);
        }
        if (internalClass != CLASS_RGBA && internalClass != CLASS_UNSUPPORTED) {
            applyClass(dst, pixels, internalClass);
        }
        return true;
    }

    private static void decodePacked(long srcRow0, long srcStride, int w, int h, int format, boolean rev, long dst) {
        boolean bgra = format == GL12.GL_BGRA;
        long d = dst;
        for (int y = 0; y < h; y++) {
            long s = srcRow0 + y * srcStride;
            for (int x = 0; x < w; x++, s += 4, d += 4) {
                int px = MemoryUtil.memGetInt(s); // packed types are host-order integers
                int c0;
                int c1;
                int c2;
                int c3;
                if (rev) { // first component in the least significant bits
                    c0 = px & 0xFF;
                    c1 = (px >>> 8) & 0xFF;
                    c2 = (px >>> 16) & 0xFF;
                    c3 = px >>> 24;
                } else {
                    c0 = px >>> 24;
                    c1 = (px >>> 16) & 0xFF;
                    c2 = (px >>> 8) & 0xFF;
                    c3 = px & 0xFF;
                }
                if (bgra) {
                    putBgra(d, c2, c1, c0, c3);
                } else {
                    putBgra(d, c0, c1, c2, c3);
                }
            }
        }
    }

    private static void decodeComponents(long srcRow0, long srcStride, int w, int h, int format, int type, long dst) {
        int n = components(format);
        int ts = typeSize(type);
        // index of the source component feeding each output channel; -1 = constant 0, -2 = constant 255
        int ri;
        int gi;
        int bi;
        int ai;
        switch (format) {
            case GL11.GL_RGBA -> { ri = 0; gi = 1; bi = 2; ai = 3; }
            case GL12.GL_BGRA -> { bi = 0; gi = 1; ri = 2; ai = 3; }
            case GL11.GL_RGB -> { ri = 0; gi = 1; bi = 2; ai = -2; }
            case GL12.GL_BGR -> { bi = 0; gi = 1; ri = 2; ai = -2; }
            case GL11.GL_RED -> { ri = 0; gi = -1; bi = -1; ai = -2; }
            case GL11.GL_GREEN -> { ri = -1; gi = 0; bi = -1; ai = -2; }
            case GL11.GL_BLUE -> { ri = -1; gi = -1; bi = 0; ai = -2; }
            case GL11.GL_ALPHA -> { ri = -1; gi = -1; bi = -1; ai = 0; }
            case GL11.GL_LUMINANCE -> { ri = 0; gi = 0; bi = 0; ai = -2; }
            case GL11.GL_LUMINANCE_ALPHA -> { ri = 0; gi = 0; bi = 0; ai = 1; }
            case GL30.GL_RG -> { ri = 0; gi = 1; bi = -1; ai = -2; }
            default -> throw new IllegalArgumentException("format " + format);
        }
        int pixelBytes = n * ts;
        boolean ubyte = type == GL11.GL_UNSIGNED_BYTE;
        long d = dst;
        for (int y = 0; y < h; y++) {
            long s = srcRow0 + y * srcStride;
            for (int x = 0; x < w; x++, s += pixelBytes, d += 4) {
                int c0;
                int c1 = 0;
                int c2 = 0;
                int c3 = 0;
                if (ubyte) {
                    c0 = MemoryUtil.memGetByte(s) & 0xFF;
                    if (n > 1) {
                        c1 = MemoryUtil.memGetByte(s + 1) & 0xFF;
                        if (n > 2) {
                            c2 = MemoryUtil.memGetByte(s + 2) & 0xFF;
                            if (n > 3) {
                                c3 = MemoryUtil.memGetByte(s + 3) & 0xFF;
                            }
                        }
                    }
                } else {
                    c0 = readComponent(s, type);
                    if (n > 1) {
                        c1 = readComponent(s + ts, type);
                        if (n > 2) {
                            c2 = readComponent(s + 2 * ts, type);
                            if (n > 3) {
                                c3 = readComponent(s + 3 * ts, type);
                            }
                        }
                    }
                }
                putBgra(d, select(ri, c0, c1, c2, c3), select(gi, c0, c1, c2, c3), select(bi, c0, c1, c2, c3), select(ai, c0, c1, c2, c3));
            }
        }
    }

    private static int select(int index, int c0, int c1, int c2, int c3) {
        return switch (index) {
            case 0 -> c0;
            case 1 -> c1;
            case 2 -> c2;
            case 3 -> c3;
            case -1 -> 0;
            default -> 255;
        };
    }

    /** One client component converted to 0..255 (normalised types are scaled like GL does, floats are clamped and rounded). */
    private static int readComponent(long a, int type) {
        switch (type) {
            case GL11.GL_UNSIGNED_BYTE:
                return MemoryUtil.memGetByte(a) & 0xFF;
            case GL11.GL_BYTE: {
                int c = MemoryUtil.memGetByte(a);
                return c <= 0 ? 0 : (c * 255 + 63) / 127;
            }
            case GL11.GL_UNSIGNED_SHORT:
                return ((MemoryUtil.memGetShort(a) & 0xFFFF) * 255 + 32767) / 65535;
            case GL11.GL_SHORT: {
                int c = MemoryUtil.memGetShort(a);
                return c <= 0 ? 0 : (c * 255 + 16383) / 32767;
            }
            case GL11.GL_UNSIGNED_INT:
                return (int) (((MemoryUtil.memGetInt(a) & 0xFFFFFFFFL) * 255L + 2147483647L) / 4294967295L);
            case GL11.GL_INT: {
                int c = MemoryUtil.memGetInt(a);
                return c <= 0 ? 0 : (int) (((long) c * 255L + 1073741823L) / 2147483647L);
            }
            case GL11.GL_FLOAT: {
                float f = MemoryUtil.memGetFloat(a);
                return f <= 0f ? 0 : f >= 1f ? 255 : (int) (f * 255f + 0.5f);
            }
            default:
                return 0;
        }
    }

    /** In-place channel rules for non-RGBA internalformats on tightly packed B8G8R8A8 texels. */
    private static void applyClass(long dst, long pixels, int cls) {
        long p = dst;
        for (long i = 0; i < pixels; i++, p += 4) {
            int px = loadLE(p);
            int r = (px >>> 16) & 0xFF;
            int g = (px >>> 8) & 0xFF;
            int a = px >>> 24;
            int out;
            switch (cls) {
                case CLASS_RGB -> out = px | 0xFF000000;
                case CLASS_LUMINANCE -> out = 0xFF000000 | r << 16 | r << 8 | r;
                case CLASS_LUMINANCE_ALPHA -> out = a << 24 | r << 16 | r << 8 | r;
                case CLASS_ALPHA -> out = a << 24 | 0x00FFFFFF; // (1,1,1,A): MODULATE keeps the fragment colour like GL's ALPHA base format
                case CLASS_INTENSITY -> out = r << 24 | r << 16 | r << 8 | r;
                case CLASS_RED -> out = 0xFF000000 | r << 16;
                case CLASS_RG -> out = 0xFF000000 | r << 16 | g << 8;
                default -> out = px;
            }
            storeLE(p, out);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // 8 bit storage -> client memory (glReadPixels / glGetTexImage)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Writes tightly packed 8 bit texels (B,G,R,A bytes when {@code srcBgra}, otherwise R,G,B,A) to client memory in the requested
     * format and type.
     *
     * @param dstRow0       address of the first destination pixel (skip rows / pixels / clip offsets already applied)
     * @param dstStride     bytes between destination rows
     * @param forceOpaque   report alpha as 1 (window framebuffers have no alpha bits)
     * @param sumLuminance  GL_LUMINANCE = R+G+B (framebuffer reads) instead of R (texture images)
     * @return false when the (format, type) pair is not supported (nothing written)
     */
    static boolean fromRgba8(long src, boolean srcBgra, int width, int height, int format, int type, long dstRow0, long dstStride,
                             boolean forceOpaque, boolean sumLuminance) {
        int bpp = bytesPerPixel(format, type);
        if (bpp <= 0 || isDepthOrStencil(format) || width <= 0 || height <= 0) {
            return false;
        }
        boolean rgbaFormat = format == GL11.GL_RGBA;
        boolean bgraFormat = format == GL12.GL_BGRA;
        boolean byteOrderOk = LITTLE && (type == GL11.GL_UNSIGNED_BYTE || type == GL12.GL_UNSIGNED_INT_8_8_8_8_REV);
        if (byteOrderOk && (rgbaFormat || bgraFormat)) {
            boolean sameOrder = bgraFormat == srcBgra;
            long rowBytes = (long) width * 4;
            if (sameOrder && !forceOpaque) {
                if (dstStride == rowBytes) {
                    MemoryUtil.memCopy(src, dstRow0, rowBytes * height);
                } else {
                    for (int y = 0; y < height; y++) {
                        MemoryUtil.memCopy(src + y * rowBytes, dstRow0 + y * dstStride, rowBytes);
                    }
                }
            } else {
                for (int y = 0; y < height; y++) {
                    long s = src + y * rowBytes;
                    long d = dstRow0 + y * dstStride;
                    for (int x = 0; x < width; x++, s += 4, d += 4) {
                        int px = MemoryUtil.memGetInt(s);
                        if (!sameOrder) {
                            px = (px & 0xFF00FF00) | ((px & 0xFF) << 16) | ((px >>> 16) & 0xFF);
                        }
                        if (forceOpaque) {
                            px |= 0xFF000000;
                        }
                        MemoryUtil.memPutInt(d, px);
                    }
                }
            }
            return true;
        }
        int n = components(format);
        boolean packed = isPacked8888(type);
        boolean rev = type == GL12.GL_UNSIGNED_INT_8_8_8_8_REV;
        int ts = packed ? 0 : typeSize(type);
        for (int y = 0; y < height; y++) {
            long s = src + (long) y * width * 4;
            long d = dstRow0 + y * dstStride;
            for (int x = 0; x < width; x++, s += 4) {
                int px = loadLE(s);
                int r;
                int g;
                int b;
                int a = forceOpaque ? 255 : px >>> 24;
                if (srcBgra) {
                    b = px & 0xFF;
                    g = (px >>> 8) & 0xFF;
                    r = (px >>> 16) & 0xFF;
                } else {
                    r = px & 0xFF;
                    g = (px >>> 8) & 0xFF;
                    b = (px >>> 16) & 0xFF;
                }
                int o0;
                int o1 = 0;
                int o2 = 0;
                int o3 = 0;
                switch (format) {
                    case GL11.GL_RGBA -> { o0 = r; o1 = g; o2 = b; o3 = a; }
                    case GL12.GL_BGRA -> { o0 = b; o1 = g; o2 = r; o3 = a; }
                    case GL11.GL_RGB -> { o0 = r; o1 = g; o2 = b; }
                    case GL12.GL_BGR -> { o0 = b; o1 = g; o2 = r; }
                    case GL11.GL_RED -> o0 = r;
                    case GL11.GL_GREEN -> o0 = g;
                    case GL11.GL_BLUE -> o0 = b;
                    case GL11.GL_ALPHA -> o0 = a;
                    case GL11.GL_LUMINANCE -> o0 = sumLuminance ? Math.min(255, r + g + b) : r;
                    case GL11.GL_LUMINANCE_ALPHA -> { o0 = sumLuminance ? Math.min(255, r + g + b) : r; o1 = a; }
                    case GL30.GL_RG -> { o0 = r; o1 = g; }
                    default -> o0 = 0;
                }
                if (packed) {
                    int v = rev ? (o0 | o1 << 8 | o2 << 16 | o3 << 24) : (o0 << 24 | o1 << 16 | o2 << 8 | o3);
                    MemoryUtil.memPutInt(d, v);
                    d += 4;
                } else {
                    writeComponent(d, type, o0);
                    if (n > 1) {
                        writeComponent(d + ts, type, o1);
                        if (n > 2) {
                            writeComponent(d + 2 * ts, type, o2);
                            if (n > 3) {
                                writeComponent(d + 3 * ts, type, o3);
                            }
                        }
                    }
                    d += (long) n * ts;
                }
            }
        }
        return true;
    }

    /** One 0..255 channel written as a client component (normalised types scaled like GL does). */
    private static void writeComponent(long a, int type, int v) {
        switch (type) {
            case GL11.GL_UNSIGNED_BYTE -> MemoryUtil.memPutByte(a, (byte) v);
            case GL11.GL_BYTE -> MemoryUtil.memPutByte(a, (byte) ((v * 127 + 127) / 255));
            case GL11.GL_UNSIGNED_SHORT -> MemoryUtil.memPutShort(a, (short) (v * 257));
            case GL11.GL_SHORT -> MemoryUtil.memPutShort(a, (short) ((v * 32767 + 127) / 255));
            case GL11.GL_UNSIGNED_INT -> MemoryUtil.memPutInt(a, v * 0x01010101);
            case GL11.GL_INT -> MemoryUtil.memPutInt(a, (int) (((long) v * 2147483647L + 127L) / 255L));
            case GL11.GL_FLOAT -> MemoryUtil.memPutFloat(a, v / 255f);
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // little endian helpers (B8G8R8A8 / R8G8B8A8 texel bytes as an int: byte 0 in the low bits)
    // ---------------------------------------------------------------------------------------------------------

    private static int loadLE(long a) {
        int v = MemoryUtil.memGetInt(a);
        return LITTLE ? v : Integer.reverseBytes(v);
    }

    private static void storeLE(long a, int v) {
        MemoryUtil.memPutInt(a, LITTLE ? v : Integer.reverseBytes(v));
    }

    private static void putBgra(long d, int r, int g, int b, int a) {
        storeLE(d, a << 24 | r << 16 | g << 8 | b);
    }
}
