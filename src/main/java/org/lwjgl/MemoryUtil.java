package org.lwjgl;

import java.nio.*;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;

/**
 * LWJGL 2 API: native memory helpers (public API and semantics of LWJGL 2.9.0). Owned by module E. The address of a buffer
 * comes from LWJGL 3: {@code getAddress0} is the address of the buffer start, {@code getAddress} adds the position (or the
 * given index). The encoders return GC managed direct buffers that include the NUL terminator, like LWJGL 2.
 */
public final class MemoryUtil {
    private static final Charset ASCII = Charset.forName("ISO-8859-1");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset UTF16 = Charset.forName("UTF-16LE");

    private MemoryUtil() {
    }

    // ---- address of the buffer start (the position is not included)

    public static long getAddress0(Buffer buffer) {
        return org.lwjgl.system.MemoryUtil.memAddress0(buffer);
    }

    public static long getAddress0Safe(Buffer buffer) {
        return buffer == null ? 0L : getAddress0(buffer);
    }

    public static long getAddress0(PointerBuffer buffer) {
        return buffer.address0();
    }

    public static long getAddress0Safe(PointerBuffer buffer) {
        return buffer == null ? 0L : getAddress0(buffer);
    }

    // ---- address of the position / of an index

    public static long getAddress(ByteBuffer buffer) {
        return org.lwjgl.system.MemoryUtil.memAddress(buffer);
    }

    public static long getAddress(ByteBuffer buffer, int position) {
        return org.lwjgl.system.MemoryUtil.memAddress(buffer, position);
    }

    public static long getAddress(ShortBuffer buffer) {
        return org.lwjgl.system.MemoryUtil.memAddress(buffer);
    }

    public static long getAddress(ShortBuffer buffer, int position) {
        return org.lwjgl.system.MemoryUtil.memAddress(buffer, position);
    }

    public static long getAddress(CharBuffer buffer) {
        return org.lwjgl.system.MemoryUtil.memAddress(buffer);
    }

    public static long getAddress(CharBuffer buffer, int position) {
        return org.lwjgl.system.MemoryUtil.memAddress(buffer, position);
    }

    public static long getAddress(IntBuffer buffer) {
        return org.lwjgl.system.MemoryUtil.memAddress(buffer);
    }

    public static long getAddress(IntBuffer buffer, int position) {
        return org.lwjgl.system.MemoryUtil.memAddress(buffer, position);
    }

    public static long getAddress(FloatBuffer buffer) {
        return org.lwjgl.system.MemoryUtil.memAddress(buffer);
    }

    public static long getAddress(FloatBuffer buffer, int position) {
        return org.lwjgl.system.MemoryUtil.memAddress(buffer, position);
    }

    public static long getAddress(LongBuffer buffer) {
        return org.lwjgl.system.MemoryUtil.memAddress(buffer);
    }

    public static long getAddress(LongBuffer buffer, int position) {
        return org.lwjgl.system.MemoryUtil.memAddress(buffer, position);
    }

    public static long getAddress(DoubleBuffer buffer) {
        return org.lwjgl.system.MemoryUtil.memAddress(buffer);
    }

    public static long getAddress(DoubleBuffer buffer, int position) {
        return org.lwjgl.system.MemoryUtil.memAddress(buffer, position);
    }

    public static long getAddress(PointerBuffer buffer) {
        return org.lwjgl.system.MemoryUtil.memAddress(buffer);
    }

    public static long getAddress(PointerBuffer buffer, int position) {
        return org.lwjgl.system.MemoryUtil.memAddress(buffer, position);
    }

    // ---- null tolerant variants

    public static long getAddressSafe(ByteBuffer buffer) {
        return buffer == null ? 0L : getAddress(buffer);
    }

    public static long getAddressSafe(ByteBuffer buffer, int position) {
        return buffer == null ? 0L : getAddress(buffer, position);
    }

    public static long getAddressSafe(ShortBuffer buffer) {
        return buffer == null ? 0L : getAddress(buffer);
    }

    public static long getAddressSafe(ShortBuffer buffer, int position) {
        return buffer == null ? 0L : getAddress(buffer, position);
    }

    public static long getAddressSafe(CharBuffer buffer) {
        return buffer == null ? 0L : getAddress(buffer);
    }

    public static long getAddressSafe(CharBuffer buffer, int position) {
        return buffer == null ? 0L : getAddress(buffer, position);
    }

    public static long getAddressSafe(IntBuffer buffer) {
        return buffer == null ? 0L : getAddress(buffer);
    }

    public static long getAddressSafe(IntBuffer buffer, int position) {
        return buffer == null ? 0L : getAddress(buffer, position);
    }

    public static long getAddressSafe(FloatBuffer buffer) {
        return buffer == null ? 0L : getAddress(buffer);
    }

    public static long getAddressSafe(FloatBuffer buffer, int position) {
        return buffer == null ? 0L : getAddress(buffer, position);
    }

    public static long getAddressSafe(LongBuffer buffer) {
        return buffer == null ? 0L : getAddress(buffer);
    }

    public static long getAddressSafe(LongBuffer buffer, int position) {
        return buffer == null ? 0L : getAddress(buffer, position);
    }

    public static long getAddressSafe(DoubleBuffer buffer) {
        return buffer == null ? 0L : getAddress(buffer);
    }

    public static long getAddressSafe(DoubleBuffer buffer, int position) {
        return buffer == null ? 0L : getAddress(buffer, position);
    }

    public static long getAddressSafe(PointerBuffer buffer) {
        return buffer == null ? 0L : getAddress(buffer);
    }

    public static long getAddressSafe(PointerBuffer buffer, int position) {
        return buffer == null ? 0L : getAddress(buffer, position);
    }

    // ---- strings

    /** ISO-8859-1 bytes of the text plus a NUL terminator (a direct buffer), null for null. */
    public static ByteBuffer encodeASCII(CharSequence text) {
        return encode(text, ASCII);
    }

    /** UTF-8 bytes of the text plus a NUL terminator (a direct buffer), null for null. */
    public static ByteBuffer encodeUTF8(CharSequence text) {
        return encode(text, UTF8);
    }

    /** UTF-16LE bytes of the text plus a NUL terminator (a direct buffer), null for null. */
    public static ByteBuffer encodeUTF16(CharSequence text) {
        return encode(text, UTF16);
    }

    private static ByteBuffer encode(CharSequence text, Charset charset) {
        if (text == null) {
            return null;
        }
        return encode(CharBuffer.wrap(new NulTerminated(text)), charset);
    }

    private static ByteBuffer encode(CharBuffer in, Charset charset) {
        CharsetEncoder encoder = charset.newEncoder(); // encoders are not thread safe
        int n = (int) (in.remaining() * encoder.averageBytesPerChar());
        ByteBuffer out = BufferUtils.createByteBuffer(n);
        if (n == 0 && in.remaining() == 0) {
            return out;
        }
        while (true) {
            CoderResult cr = in.hasRemaining() ? encoder.encode(in, out, true) : CoderResult.UNDERFLOW;
            if (cr.isUnderflow()) {
                cr = encoder.flush(out);
            }
            if (cr.isUnderflow()) {
                break;
            }
            if (cr.isOverflow()) {
                n = 2 * n + 1; // ensure progress, n might be 0
                ByteBuffer bigger = BufferUtils.createByteBuffer(n);
                out.flip();
                bigger.put(out);
                out = bigger;
                continue;
            }
            try {
                cr.throwException();
            } catch (CharacterCodingException e) {
                throw new RuntimeException(e);
            }
        }
        out.flip();
        return out;
    }

    /** Decodes the remaining bytes as ISO-8859-1 (a NUL is part of the text, like in LWJGL 2). */
    public static String decodeASCII(ByteBuffer buffer) {
        return decode(buffer, ASCII);
    }

    public static String decodeUTF8(ByteBuffer buffer) {
        return decode(buffer, UTF8);
    }

    public static String decodeUTF16(ByteBuffer buffer) {
        return decode(buffer, UTF16);
    }

    private static String decode(ByteBuffer buffer, Charset charset) {
        if (buffer == null) {
            return null;
        }
        CharsetDecoder decoder = charset.newDecoder();
        int n = (int) (buffer.remaining() * decoder.averageCharsPerByte());
        CharBuffer out = BufferUtils.createCharBuffer(n);
        if (n == 0 && buffer.remaining() == 0) {
            return "";
        }
        decoder.reset();
        while (true) {
            CoderResult cr = buffer.hasRemaining() ? decoder.decode(buffer, out, true) : CoderResult.UNDERFLOW;
            if (cr.isUnderflow()) {
                cr = decoder.flush(out);
            }
            if (cr.isUnderflow()) {
                break;
            }
            if (cr.isOverflow()) {
                n = 2 * n + 1;
                CharBuffer bigger = BufferUtils.createCharBuffer(n);
                out.flip();
                bigger.put(out);
                out = bigger;
                continue;
            }
            try {
                cr.throwException();
            } catch (CharacterCodingException e) {
                throw new RuntimeException(e);
            }
        }
        out.flip();
        return out.toString();
    }

    /** A CharSequence that appends a NUL to the text. */
    private static final class NulTerminated implements CharSequence {
        private final CharSequence source;

        private NulTerminated(CharSequence source) {
            this.source = source;
        }

        @Override
        public int length() {
            return source.length() + 1;
        }

        @Override
        public char charAt(int index) {
            return index == source.length() ? '\0' : source.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            if (end <= source.length()) {
                return source.subSequence(start, end);
            }
            return new NulTerminated(source.subSequence(start, source.length()));
        }

        @Override
        public String toString() {
            return source + "\0";
        }
    }
}
