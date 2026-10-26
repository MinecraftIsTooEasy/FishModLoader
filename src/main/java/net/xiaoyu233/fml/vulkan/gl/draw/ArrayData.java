package net.xiaoyu233.fml.vulkan.gl.draw;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.gl.GLErrors;
import net.xiaoyu233.fml.vulkan.gl.GLUnsupported;
import net.xiaoyu233.fml.vulkan.pipeline.VertexLayout;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.util.Arrays;

/**
 * Turns the enabled client arrays (read when the draw is issued, like GL) into one interleaved Vulkan vertex binding.
 * <ul>
 * <li><b>Interleaved fast path</b> (the Tessellator: one 32-byte stride, every array in the same window): when every enabled array
 * has a format the shaders fetch natively (float positions/texcoords/colors/normals, ubyte colors, byte normals, short texcoord
 * pairs), the same stride (a multiple of 4) and lies inside one stride window, the vertex bytes are used as they are and only the
 * relative offsets form the {@link VertexLayout}.</li>
 * <li><b>Repack path</b> (everything else): each attribute is converted (shorts/ints/doubles to float, 3-byte ubyte colors to RGBA8,
 * normalization of colors and normals like GL) into a freshly packed single-binding vertex whose stride is a multiple of 4 and whose
 * attributes never reach beyond the stride, which is what VK_KHR_portability_subset (MoltenVK) requires.</li>
 * </ul>
 * Reads are bounds-checked against the client buffer / buffer object (a draw that would read outside is refused with
 * GL_INVALID_OPERATION instead of crashing the JVM). The result is left in static fields; render thread only.
 */
final class ArrayData {
    // result of prepare()
    static VertexLayout layout;
    /** Stride of the prepared vertices. */
    static int stride;
    /** Address of the prepared vertex bytes (client memory on the fast path, a scratch buffer otherwise). */
    static long src;
    /** Bytes at {@link #src} that carry data. */
    static int srcBytes;
    /** Bytes the vertices occupy in the destination (vertex count * stride); {@code >= srcBytes}. */
    static int allocBytes;

    private static final int MAX_STRIDE = 2048; // maxVertexInputBindingStride guaranteed by Vulkan
    /** Largest vertex data of one draw; bigger draws are refused with GL_OUT_OF_MEMORY. */
    private static final long MAX_DRAW_BYTES = 256L << 20;

    private static final int ATTRS = VertexLayout.ATTR_COUNT;
    private static final int[] FMT = new int[ATTRS];
    private static final int[] OFF = new int[ATTRS];
    private static final int[] BIND = new int[ATTRS];
    private static final int[] STR = new int[VertexLayout.MAX_BINDINGS];
    private static final int[] lastFmt = new int[ATTRS];
    private static final int[] lastOff = new int[ATTRS];
    private static int lastStride = -1;
    private static VertexLayout lastLayout;

    // per attribute source description
    private static final ClientState.ArrayState[] state = new ClientState.ArrayState[ATTRS];
    private static final boolean[] present = new boolean[ATTRS];
    /** Address of the first uploaded vertex of the attribute. */
    private static final long[] base = new long[ATTRS];
    /** Exclusive end of the memory the attribute may read. */
    private static final long[] limit = new long[ATTRS];
    private static final int[] sSize = new int[ATTRS];
    private static final int[] sType = new int[ATTRS];
    private static final int[] sStride = new int[ATTRS];
    private static final int[] sElem = new int[ATTRS];
    /** VkFormat the shader can fetch from the source bytes as they are (0 = needs conversion) and its byte size. */
    private static final int[] dFormat = new int[ATTRS];
    private static final int[] dBytes = new int[ATTRS];

    private static final NativeBuf scratch = new NativeBuf();

    // conversion kinds
    private static final int C_COPY = 0;
    private static final int C_NUM2F = 1;
    private static final int C_COLOR_UB3 = 2;
    private static final int C_COLOR2F = 3;
    private static final int C_NORMAL_B3 = 4;
    private static final int C_NORMAL2F = 5;

    // resolve() result
    private static long rBase;
    private static long rLimit;

    private static boolean warnedMissing;
    private static boolean warnedOutOfBounds;
    private static boolean warnedUnits;

    private ArrayData() {
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // checks
    // ------------------------------------------------------------------------------------------------------------------------

    /**
     * True when a draw can use the client arrays: the vertex array is enabled. A draw with only generic vertex attributes enabled
     * (shader based rendering) is a logged no-op.
     */
    static boolean canDraw() {
        if (!ClientState.vertex().enabled) {
            if (ClientState.genericEnableMask() != 0) {
                GLUnsupported.call("glDraw* with generic vertex attribute arrays (shader programs are not supported)");
            }
            return false;
        }
        if (!warnedUnits && ClientState.unsupportedTexUnitEnabled()) {
            warnedUnits = true;
            GLUnsupported.call("texture coordinate arrays of texture units 2 and above");
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // prepare
    // ------------------------------------------------------------------------------------------------------------------------

    /**
     * Prepares the vertices {@code [first, first + n)} of the enabled arrays. Returns false when nothing can be drawn (invalid or
     * unreadable arrays; the GL error is set).
     */
    static boolean prepare(int first, int n) {
        state[VertexLayout.ATTR_POSITION] = ClientState.vertex();
        state[VertexLayout.ATTR_COLOR] = ClientState.color();
        state[VertexLayout.ATTR_TEX0] = ClientState.tex(0);
        state[VertexLayout.ATTR_TEX1] = ClientState.tex(1);
        state[VertexLayout.ATTR_NORMAL] = ClientState.normal();
        for (int a = 0; a < ATTRS; a++) {
            if (!gather(a, state[a], first, n)) {
                return false;
            }
        }
        if (tryInterleaved(n)) {
            return true;
        }
        return repack(n);
    }

    private static boolean gather(int attr, ClientState.ArrayState s, int first, int n) {
        present[attr] = false;
        if (!s.enabled) {
            return true;
        }
        if (!resolve(s)) {
            if (!warnedMissing) {
                warnedMissing = true;
                VkLog.LOGGER.warn("Draw refused: enabled vertex array {} has no valid pointer (null pointer or deleted buffer object)", attr);
            }
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return false;
        }
        int typeSize = ClientState.typeSize(s.type);
        int size = s.size;
        int elem = size * typeSize;
        int st = s.stride != 0 ? s.stride : elem;
        long firstAddr = rBase + (long) first * st;
        long lastEnd = firstAddr + (long) (n - 1) * st + elem;
        if (firstAddr < rBase || lastEnd > rLimit) {
            if (!warnedOutOfBounds) {
                warnedOutOfBounds = true;
                VkLog.LOGGER.warn("Draw refused: vertex array {} (first {}, count {}, stride {}) reads beyond its buffer", attr, first, n, st);
            }
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return false;
        }
        present[attr] = true;
        base[attr] = firstAddr;
        limit[attr] = rLimit;
        sSize[attr] = size;
        sType[attr] = s.type;
        sStride[attr] = st;
        sElem[attr] = elem;
        directFormat(attr, size, s.type);
        return true;
    }

    /** Resolves a pointer to {@link #rBase} / {@link #rLimit}; false when it is null or its buffer object is gone. */
    private static boolean resolve(ClientState.ArrayState s) {
        if (s.buffer != 0) {
            BufferObjects.BufferObject b = BufferObjects.get(s.buffer);
            if (b == null || b.address == 0L || s.pointer < 0 || s.pointer > b.size) {
                return false;
            }
            rBase = b.address + s.pointer;
            rLimit = b.address + b.size;
            return true;
        }
        if (s.pointer == 0L) {
            return false;
        }
        rBase = s.pointer;
        rLimit = s.limit != 0L ? s.limit : Long.MAX_VALUE;
        return true;
    }

    /** The VkFormat the shader can fetch directly from this array (dFormat/dBytes), or 0. */
    private static void directFormat(int attr, int size, int type) {
        int f = 0;
        int bytes = 0;
        switch (attr) {
            case VertexLayout.ATTR_POSITION:
                if (type == GL11.GL_FLOAT) {
                    f = floatFormat(size);
                    bytes = 4 * size;
                }
                break;
            case VertexLayout.ATTR_COLOR:
                if (type == GL11.GL_UNSIGNED_BYTE && size == 4) {
                    f = VK10.VK_FORMAT_R8G8B8A8_UNORM;
                    bytes = 4;
                } else if (type == GL11.GL_FLOAT && size >= 3) {
                    f = floatFormat(size);
                    bytes = 4 * size;
                }
                break;
            case VertexLayout.ATTR_TEX0:
            case VertexLayout.ATTR_TEX1:
                if (type == GL11.GL_FLOAT) {
                    f = floatFormat(size);
                    bytes = 4 * size;
                } else if (type == GL11.GL_SHORT && size == 2) {
                    f = VK10.VK_FORMAT_R16G16_SNORM;
                    bytes = 4;
                }
                break;
            case VertexLayout.ATTR_NORMAL:
                if (type == GL11.GL_BYTE) {
                    f = VK10.VK_FORMAT_R8G8B8A8_SNORM; // 3 byte normals read as 4 bytes; the 4th byte is ignored by the shader
                    bytes = 4;
                } else if (type == GL11.GL_FLOAT) {
                    f = VK10.VK_FORMAT_R32G32B32_SFLOAT;
                    bytes = 12;
                }
                break;
            default:
                break;
        }
        dFormat[attr] = f;
        dBytes[attr] = bytes;
    }

    private static int floatFormat(int size) {
        switch (size) {
            case 1:
                return VK10.VK_FORMAT_R32_SFLOAT;
            case 2:
                return VK10.VK_FORMAT_R32G32_SFLOAT;
            case 3:
                return VK10.VK_FORMAT_R32G32B32_SFLOAT;
            default:
                return VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // interleaved fast path
    // ------------------------------------------------------------------------------------------------------------------------

    private static boolean tryInterleaved(int n) {
        int s = -1;
        long minBase = Long.MAX_VALUE;
        for (int a = 0; a < ATTRS; a++) {
            if (!present[a]) {
                continue;
            }
            if (dFormat[a] == 0) {
                return false;
            }
            if (s < 0) {
                s = sStride[a];
            } else if (s != sStride[a]) {
                return false;
            }
            minBase = Math.min(minBase, base[a]);
        }
        if (s < 4 || (s & 3) != 0 || s > MAX_STRIDE || (long) n * s > MAX_DRAW_BYTES) {
            return false;
        }
        int maxEnd = 0;
        for (int a = 0; a < ATTRS; a++) {
            FMT[a] = 0;
            OFF[a] = 0;
            if (!present[a]) {
                continue;
            }
            long off = base[a] - minBase;
            if (off + dBytes[a] > s || (off & 3) != 0) {
                return false; // outside the stride window or misaligned for the vertex fetch
            }
            // the format may read a little past the last element (3 byte normals as RGBA8): the memory must be there
            if (base[a] + (long) (n - 1) * s + dBytes[a] > limit[a]) {
                return false;
            }
            FMT[a] = dFormat[a];
            OFF[a] = (int) off;
            maxEnd = Math.max(maxEnd, (int) off + dBytes[a]);
        }
        layout = layoutFor(s);
        stride = s;
        src = minBase;
        long bytes = (long) (n - 1) * s + maxEnd;
        srcBytes = (int) bytes;
        allocBytes = n * s;
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // repack path
    // ------------------------------------------------------------------------------------------------------------------------

    private static boolean repack(int n) {
        int[] conv = CONV;
        int off = 0;
        for (int a = 0; a < ATTRS; a++) {
            FMT[a] = 0;
            OFF[a] = 0;
            if (!present[a]) {
                continue;
            }
            int size = sSize[a];
            int type = sType[a];
            int fmt;
            int bytes;
            int kind;
            switch (a) {
                case VertexLayout.ATTR_COLOR:
                    if (type == GL11.GL_UNSIGNED_BYTE) {
                        fmt = VK10.VK_FORMAT_R8G8B8A8_UNORM;
                        bytes = 4;
                        kind = size == 4 ? C_COPY : C_COLOR_UB3;
                    } else if (type == GL11.GL_FLOAT) {
                        fmt = floatFormat(size);
                        bytes = 4 * size;
                        kind = C_COPY;
                    } else {
                        fmt = VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
                        bytes = 16;
                        kind = C_COLOR2F;
                    }
                    break;
                case VertexLayout.ATTR_NORMAL:
                    if (type == GL11.GL_BYTE) {
                        fmt = VK10.VK_FORMAT_R8G8B8A8_SNORM;
                        bytes = 4;
                        kind = C_NORMAL_B3;
                    } else if (type == GL11.GL_FLOAT) {
                        fmt = VK10.VK_FORMAT_R32G32B32_SFLOAT;
                        bytes = 12;
                        kind = C_COPY;
                    } else {
                        fmt = VK10.VK_FORMAT_R32G32B32_SFLOAT;
                        bytes = 12;
                        kind = C_NORMAL2F;
                    }
                    break;
                case VertexLayout.ATTR_TEX0:
                case VertexLayout.ATTR_TEX1:
                    if (type == GL11.GL_SHORT && size == 2) {
                        fmt = VK10.VK_FORMAT_R16G16_SNORM;
                        bytes = 4;
                        kind = C_COPY;
                        break;
                    }
                    // fall through: floats / numeric conversion
                default: // position and the remaining texcoord forms
                    fmt = floatFormat(size);
                    bytes = 4 * size;
                    kind = type == GL11.GL_FLOAT ? C_COPY : C_NUM2F;
                    break;
            }
            FMT[a] = fmt;
            OFF[a] = off;
            conv[a] = kind;
            off += bytes;
        }
        int s = off;
        long total = (long) n * s;
        if (total > MAX_DRAW_BYTES) {
            GLErrors.set(GL11.GL_OUT_OF_MEMORY);
            return false;
        }
        long dst0 = scratch.ensure((int) total);
        for (int a = 0; a < ATTRS; a++) {
            if (present[a]) {
                convert(conv[a], a, dst0 + OFF[a], s, n);
            }
        }
        layout = layoutFor(s);
        stride = s;
        src = dst0;
        srcBytes = (int) total;
        allocBytes = (int) total;
        return true;
    }

    private static final int[] CONV = new int[ATTRS];

    private static void convert(int kind, int a, long dst, int dstStride, int n) {
        long s = base[a];
        int ss = sStride[a];
        int size = sSize[a];
        int type = sType[a];
        switch (kind) {
            case C_COPY: {
                int bytes = sElem[a];
                for (int i = 0; i < n; i++, s += ss, dst += dstStride) {
                    MemoryUtil.memCopy(s, dst, bytes);
                }
                break;
            }
            case C_NUM2F:
                for (int i = 0; i < n; i++, s += ss, dst += dstStride) {
                    for (int c = 0; c < size; c++) {
                        MemoryUtil.memPutFloat(dst + 4L * c, (float) readNumber(type, s + (long) c * ClientState.typeSize(type)));
                    }
                }
                break;
            case C_COLOR_UB3:
                for (int i = 0; i < n; i++, s += ss, dst += dstStride) {
                    MemoryUtil.memPutByte(dst, MemoryUtil.memGetByte(s));
                    MemoryUtil.memPutByte(dst + 1, MemoryUtil.memGetByte(s + 1));
                    MemoryUtil.memPutByte(dst + 2, MemoryUtil.memGetByte(s + 2));
                    MemoryUtil.memPutByte(dst + 3, (byte) 0xFF);
                }
                break;
            case C_COLOR2F:
                for (int i = 0; i < n; i++, s += ss, dst += dstStride) {
                    int ts = ClientState.typeSize(type);
                    for (int c = 0; c < 4; c++) {
                        float v = c < size ? clamp01(normalized(type, s + (long) c * ts)) : 1f;
                        MemoryUtil.memPutFloat(dst + 4L * c, v);
                    }
                }
                break;
            case C_NORMAL_B3:
                for (int i = 0; i < n; i++, s += ss, dst += dstStride) {
                    MemoryUtil.memPutByte(dst, MemoryUtil.memGetByte(s));
                    MemoryUtil.memPutByte(dst + 1, MemoryUtil.memGetByte(s + 1));
                    MemoryUtil.memPutByte(dst + 2, MemoryUtil.memGetByte(s + 2));
                    MemoryUtil.memPutByte(dst + 3, (byte) 0);
                }
                break;
            default: // C_NORMAL2F
                for (int i = 0; i < n; i++, s += ss, dst += dstStride) {
                    int ts = ClientState.typeSize(type);
                    for (int c = 0; c < 3; c++) {
                        MemoryUtil.memPutFloat(dst + 4L * c, normalized(type, s + (long) c * ts));
                    }
                }
                break;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // layout cache
    // ------------------------------------------------------------------------------------------------------------------------

    /** The interned layout for FMT/OFF and a single binding 0 of the given stride (the last result is cached: no work per draw). */
    private static VertexLayout layoutFor(int s) {
        VertexLayout l = lastLayout;
        if (l != null && s == lastStride && Arrays.equals(FMT, lastFmt) && Arrays.equals(OFF, lastOff)) {
            return l;
        }
        STR[0] = s;
        l = VertexLayout.of(FMT, BIND, OFF, STR);
        System.arraycopy(FMT, 0, lastFmt, 0, ATTRS);
        System.arraycopy(OFF, 0, lastOff, 0, ATTRS);
        lastStride = s;
        lastLayout = l;
        return l;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // number conversion (GL rules)
    // ------------------------------------------------------------------------------------------------------------------------

    /** The component as a plain number (positions, texture coordinates). */
    static double readNumber(int type, long address) {
        switch (type) {
            case GL11.GL_BYTE:
                return MemoryUtil.memGetByte(address);
            case GL11.GL_UNSIGNED_BYTE:
                return MemoryUtil.memGetByte(address) & 0xFF;
            case GL11.GL_SHORT:
                return MemoryUtil.memGetShort(address);
            case GL11.GL_UNSIGNED_SHORT:
                return MemoryUtil.memGetShort(address) & 0xFFFF;
            case GL11.GL_INT:
                return MemoryUtil.memGetInt(address);
            case GL11.GL_UNSIGNED_INT:
                return MemoryUtil.memGetInt(address) & 0xFFFFFFFFL;
            case GL11.GL_FLOAT:
                return MemoryUtil.memGetFloat(address);
            default:
                return MemoryUtil.memGetDouble(address);
        }
    }

    /** The component normalized like GL does for colors and normals: unsigned c / max, signed (2c + 1) / max, floats as they are. */
    static float normalized(int type, long address) {
        switch (type) {
            case GL11.GL_BYTE:
                return (2f * MemoryUtil.memGetByte(address) + 1f) / 255f;
            case GL11.GL_UNSIGNED_BYTE:
                return (MemoryUtil.memGetByte(address) & 0xFF) / 255f;
            case GL11.GL_SHORT:
                return (2f * MemoryUtil.memGetShort(address) + 1f) / 65535f;
            case GL11.GL_UNSIGNED_SHORT:
                return (MemoryUtil.memGetShort(address) & 0xFFFF) / 65535f;
            case GL11.GL_INT:
                return (float) ((2.0 * MemoryUtil.memGetInt(address) + 1.0) / 4294967295.0);
            case GL11.GL_UNSIGNED_INT:
                return (float) ((MemoryUtil.memGetInt(address) & 0xFFFFFFFFL) / 4294967295.0);
            case GL11.GL_FLOAT:
                return MemoryUtil.memGetFloat(address);
            default:
                return (float) MemoryUtil.memGetDouble(address);
        }
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // glArrayElement
    // ------------------------------------------------------------------------------------------------------------------------

    // element values read by readElement()
    static final float[] ePos = new float[4];
    static final float[] eColor = new float[4];
    static final float[] eNormal = new float[3];
    static final float[] eTex0 = new float[4];
    static final float[] eTex1 = new float[4];
    /** Bit {@code 1 << VertexLayout.ATTR_*} for every attribute read from an array (the others keep the current value). */
    static int ePresent;

    /** Reads vertex {@code i} of the enabled arrays into the e* fields; false when the position array can not be read. */
    static boolean readElement(int i) {
        ePresent = 0;
        if (i < 0) {
            return false;
        }
        for (int a = 0; a < ATTRS; a++) {
            ClientState.ArrayState s = a == VertexLayout.ATTR_POSITION ? ClientState.vertex()
                    : a == VertexLayout.ATTR_COLOR ? ClientState.color()
                    : a == VertexLayout.ATTR_TEX0 ? ClientState.tex(0)
                    : a == VertexLayout.ATTR_TEX1 ? ClientState.tex(1) : ClientState.normal();
            if (!s.enabled) {
                if (a == VertexLayout.ATTR_POSITION) {
                    return false;
                }
                continue;
            }
            if (!resolve(s)) {
                GLErrors.set(GL11.GL_INVALID_OPERATION);
                return false;
            }
            int ts = ClientState.typeSize(s.type);
            int elem = s.size * ts;
            int st = s.stride != 0 ? s.stride : elem;
            long addr = rBase + (long) i * st;
            if (addr < rBase || addr + elem > rLimit) {
                GLErrors.set(GL11.GL_INVALID_OPERATION);
                return false;
            }
            int size = s.size;
            switch (a) {
                case VertexLayout.ATTR_POSITION:
                    ePos[0] = ePos[1] = ePos[2] = 0f;
                    ePos[3] = 1f;
                    for (int c = 0; c < size; c++) {
                        ePos[c] = (float) readNumber(s.type, addr + (long) c * ts);
                    }
                    break;
                case VertexLayout.ATTR_COLOR:
                    eColor[0] = eColor[1] = eColor[2] = 0f;
                    eColor[3] = 1f;
                    for (int c = 0; c < size; c++) {
                        eColor[c] = clamp01(normalized(s.type, addr + (long) c * ts));
                    }
                    break;
                case VertexLayout.ATTR_NORMAL:
                    for (int c = 0; c < 3; c++) {
                        eNormal[c] = normalized(s.type, addr + (long) c * ts);
                    }
                    break;
                default: {
                    float[] t = a == VertexLayout.ATTR_TEX0 ? eTex0 : eTex1;
                    t[0] = t[1] = t[2] = 0f;
                    t[3] = 1f;
                    for (int c = 0; c < size; c++) {
                        t[c] = (float) readNumber(s.type, addr + (long) c * ts);
                    }
                    break;
                }
            }
            ePresent |= 1 << a;
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // element indices
    // ------------------------------------------------------------------------------------------------------------------------

    static long scanMin;
    static long scanMax;

    /** Minimum and maximum (unsigned) of {@code count} indices into {@link #scanMin}/{@link #scanMax}. */
    static void scanIndices(long address, int type, int count) {
        long min = Long.MAX_VALUE;
        long max = -1L;
        for (int i = 0; i < count; i++) {
            long v;
            switch (type) {
                case GL11.GL_UNSIGNED_BYTE:
                    v = MemoryUtil.memGetByte(address + i) & 0xFFL;
                    break;
                case GL11.GL_UNSIGNED_SHORT:
                    v = MemoryUtil.memGetShort(address + ((long) i << 1)) & 0xFFFFL;
                    break;
                default:
                    v = MemoryUtil.memGetInt(address + ((long) i << 2)) & 0xFFFFFFFFL;
                    break;
            }
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
        }
        scanMin = min;
        scanMax = max;
    }

    static void shutdown() {
        scratch.free();
        lastLayout = null;
        lastStride = -1;
    }
}
