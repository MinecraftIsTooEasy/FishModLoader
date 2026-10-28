package net.xiaoyu233.fml.vulkan.window;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Win32 RAWINPUT struct offsets and a pure decoder for a GetRawInputBuffer result. Contains no native calls and no linker
 * lookup, so it loads (and is unit-testable) on every platform.
 *
 * <pre>
 * RAWINPUTHEADER: dwType u32 @0, dwSize u32 @4, hDevice ptr @8, wParam ptr @8+P  (24 bytes on x64, 16 on x86)
 * RAWMOUSE (follows header): usFlags u16 @0, ulButtons u32 @4, ulRawButtons u32 @8, lLastX i32 @12, lLastY i32 @16, ulExtra u32 @20
 * </pre>
 */
public final class RawInputStructs {
    public static final int RIM_TYPEMOUSE = 0;
    public static final int MOUSE_MOVE_ABSOLUTE = 0x01;
    public static final int MOUSE_VIRTUAL_DESKTOP = 0x02;
    public static final int RIDEV_REMOVE = 0x01;
    public static final int RIDEV_INPUTSINK = 0x100;
    public static final int RAWINPUTDEVICE_SIZE = 16; // x64 (x86: 12; the feature is only enabled on 64-bit)

    /** sizeof(RAWINPUTHEADER) for the given pointer size (4 or 8). */
    public static int headerSize(int ptrSize) {
        return 16 + 2 * (ptrSize - 4) ; // 16 on x86, 24 on x64
    }

    public static int mouseFlagsOffset(int ptrSize) {
        return headerSize(ptrSize);
    }

    public static int lastXOffset(int ptrSize) {
        return headerSize(ptrSize) + 12;
    }

    public static int lastYOffset(int ptrSize) {
        return headerSize(ptrSize) + 16;
    }

    /** NEXTRAWINPUTBLOCK: advance by dwSize, rounded up to the pointer size. */
    public static long nextBlock(long offset, int dwSize, int ptrSize) {
        long n = offset + dwSize;
        return (n + ptrSize - 1) & -(long) ptrSize;
    }

    /**
     * Decodes up to {@code count} RAWINPUT blocks from {@code buf} (a GetRawInputBuffer result), adding relative mouse motion
     * to {@code out[0]} (x) and {@code out[1]} (y). Absolute-position events are ignored.
     *
     * @return the number of blocks that were actually decoded (mouse or not)
     */
    public static int decode(MemorySegment buf, int count, int ptrSize, long[] out) {
        long limit = buf.byteSize();
        int hdr = headerSize(ptrSize);
        long off = 0;
        int n = 0;
        while (n < count && off + hdr + 20 <= limit) {
            int type = buf.get(ValueLayout.JAVA_INT_UNALIGNED, off);
            int size = buf.get(ValueLayout.JAVA_INT_UNALIGNED, off + 4);
            if (size < hdr) {
                break;
            }
            if (type == RIM_TYPEMOUSE) {
                int flags = buf.get(ValueLayout.JAVA_SHORT_UNALIGNED, off + mouseFlagsOffset(ptrSize)) & 0xFFFF;
                if ((flags & MOUSE_MOVE_ABSOLUTE) == 0) {
                    out[0] += buf.get(ValueLayout.JAVA_INT_UNALIGNED, off + lastXOffset(ptrSize));
                    out[1] += buf.get(ValueLayout.JAVA_INT_UNALIGNED, off + lastYOffset(ptrSize));
                }
            }
            n++;
            off = nextBlock(off, size, ptrSize);
        }
        return n;
    }

    private RawInputStructs() {
    }
}
