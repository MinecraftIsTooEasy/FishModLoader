package net.xiaoyu233.fml.vulkan.gl;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.gl.draw.DisplayLists;
import net.xiaoyu233.fml.vulkan.gl.state.GLState;
import net.xiaoyu233.fml.vulkan.render.legacy.StateMirror;
import net.xiaoyu233.fml.vulkan.texture.TextureManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * GL entry points owned by this facade (see ../docs/ARCHITECTURE.md, outside the repo). The LWJGL 2 compat classes in
 * org.lwjgl.opengl delegate here with the exact LWJGL 2 descriptors (vendor suffixes stripped).
 *
 * <p>Buffer arguments follow LWJGL 2: they must be direct, the data starts at the buffer position, a buffer that is too small is an
 * IllegalArgumentException and the buffer position is never modified (reads and writes go to native memory through the buffer address).
 * Functions that GL compiles into display lists record an internal implementation (never the public entry point, so replaying a list
 * from inside another list cannot re-record). glTexImage2D / glTexSubImage2D are compiled too: like GL they store a copy of the pixel
 * data (and the unpack state) in the list and upload it in order with the recorded glBindTexture / glTexParameter calls when the list
 * runs. (MITE creates its cloud texture lazily while the cloud lists are compiled: executing the upload at compile time would fill
 * the texture bound before the list, not the one the list binds.)
 */
@SuppressWarnings("unused")
public final class GLTexImpl {
    private GLTexImpl() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // binding, names
    // ---------------------------------------------------------------------------------------------------------

    /** Pulls the native-dirty Draw groups before a texture op reads the bound texture / active unit (ARCHITECTURE-NATIVE 1.16.3). */
    private static void pullNative() {
        if (StateMirror.nativeDirty != 0 && GLState.checkRenderThread()) StateMirror.syncNativeToLegacy();
    }

    public static void glBindTexture(int target, int texture) {
        if (!GLState.checkRenderThread()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> bindTexture(target, texture))) return;
        bindTexture(target, texture);
    }

    private static void bindTexture(int target, int texture) {
        if (target != GL11.GL_TEXTURE_2D) {
            // 1D / 3D / cube map / rectangle textures are not implemented: they must not disturb the 2D binding
            GLUnsupported.call("glBindTexture(target 0x" + Integer.toHexString(target) + ")");
            return;
        }
        // skip the volatile write when the bit is already set (avoids a locked store per glBindTexture)
        final int bit = net.xiaoyu233.fml.vulkan.render.state.LegacyGroups.TEXTURE_BIND;
        if ((net.xiaoyu233.fml.vulkan.render.legacy.StateMirror.legacyDirty & bit) != bit) {
            net.xiaoyu233.fml.vulkan.render.legacy.StateMirror.legacyDirty |= bit;
        }
        TextureManager.bindTexture(GLState.facade().activeTexture(), texture);
    }

    public static void glDeleteTextures(int textures) {
        TextureManager.deleteTextures(textures);
    }

    public static void glDeleteTextures(IntBuffer textures) {
        TextureManager.deleteTextures(textures);
    }

    public static int glGenTextures() {
        return TextureManager.genTextures();
    }

    public static void glGenTextures(IntBuffer textures) {
        TextureManager.genTextures(textures);
    }

    public static boolean glIsTexture(int texture) {
        return TextureManager.isTexture(texture);
    }

    // ---------------------------------------------------------------------------------------------------------
    // copy from the framebuffer, mipmaps
    // ---------------------------------------------------------------------------------------------------------

    public static void glCopyTexImage2D(int target, int level, int internalformat, int x, int y, int width, int height, int border) {
        pullNative();
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> TextureManager.copyTexImage2D(target, level, internalformat, x, y, width, height, border))) return;
        TextureManager.copyTexImage2D(target, level, internalformat, x, y, width, height, border);
    }

    public static void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        pullNative();
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> TextureManager.copyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height))) return;
        TextureManager.copyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
    }

    public static void glGenerateMipmap(int target) {
        pullNative();
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> TextureManager.generateMipmap(target))) return;
        TextureManager.generateMipmap(target);
    }

    // ---------------------------------------------------------------------------------------------------------
    // queries
    // ---------------------------------------------------------------------------------------------------------

    public static void glGetTexImage(int target, int level, int format, int type, long pixels_buffer_offset) {
        pullNative();
        GLUnsupported.call("glGetTexImage"); // pixel buffer objects are not supported
    }

    public static void glGetTexImage(int target, int level, int format, int type, ByteBuffer pixels) {
        pullNative();
        TextureManager.getTexImage(target, level, format, type, address(pixels), bytes(pixels));
    }

    public static void glGetTexImage(int target, int level, int format, int type, DoubleBuffer pixels) {
        pullNative();
        GLUnsupported.call("glGetTexImage");
    }

    public static void glGetTexImage(int target, int level, int format, int type, FloatBuffer pixels) {
        pullNative();
        TextureManager.getTexImage(target, level, format, type, address(pixels), bytes(pixels));
    }

    public static void glGetTexImage(int target, int level, int format, int type, IntBuffer pixels) {
        pullNative();
        TextureManager.getTexImage(target, level, format, type, address(pixels), bytes(pixels));
    }

    public static void glGetTexImage(int target, int level, int format, int type, ShortBuffer pixels) {
        pullNative();
        TextureManager.getTexImage(target, level, format, type, address(pixels), bytes(pixels));
    }

    public static void glGetTexLevelParameter(int target, int level, int pname, FloatBuffer params) {
        pullNative();
        needRemaining(params, 1);
        params.put(params.position(), glGetTexLevelParameterf(target, level, pname));
    }

    public static void glGetTexLevelParameter(int target, int level, int pname, IntBuffer params) {
        pullNative();
        needRemaining(params, 1);
        params.put(params.position(), glGetTexLevelParameteri(target, level, pname));
    }

    public static float glGetTexLevelParameterf(int target, int level, int pname) {
        pullNative();
        return glGetTexLevelParameteri(target, level, pname);
    }

    public static int glGetTexLevelParameteri(int target, int level, int pname) {
        pullNative();
        return TextureManager.getTexLevelParameteri(target, level, pname);
    }

    public static void glGetTexParameter(int target, int pname, FloatBuffer params) {
        pullNative();
        needRemaining(params, 1);
        if (pname == GL11.GL_TEXTURE_BORDER_COLOR && params.remaining() >= 4) {
            float[] c = new float[4];
            TextureManager.getTexBorderColor(target, c);
            for (int i = 0; i < 4; i++) {
                params.put(params.position() + i, c[i]);
            }
            return;
        }
        params.put(params.position(), glGetTexParameterf(target, pname));
    }

    public static void glGetTexParameter(int target, int pname, IntBuffer params) {
        pullNative();
        needRemaining(params, 1);
        if (pname == GL11.GL_TEXTURE_BORDER_COLOR && params.remaining() >= 4) {
            float[] c = new float[4];
            TextureManager.getTexBorderColor(target, c);
            for (int i = 0; i < 4; i++) {
                // GL maps a floating point colour component to the full integer range
                params.put(params.position() + i, (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (double) c[i] * Integer.MAX_VALUE)));
            }
            return;
        }
        params.put(params.position(), glGetTexParameteri(target, pname));
    }

    public static float glGetTexParameterf(int target, int pname) {
        pullNative();
        return TextureManager.getTexParameterf(target, pname);
    }

    public static int glGetTexParameteri(int target, int pname) {
        pullNative();
        return TextureManager.getTexParameteri(target, pname);
    }

    // ---------------------------------------------------------------------------------------------------------
    // pixel store, read back
    // ---------------------------------------------------------------------------------------------------------

    public static void glPixelStoref(int pname, float param) {
        glPixelStorei(pname, Math.round(param));
    }

    public static void glPixelStorei(int pname, int param) {
        TextureManager.setPixelStorei(pname, param);
    }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, long pixels_buffer_offset) {
        GLUnsupported.call("glReadPixels"); // pixel buffer objects are not supported
    }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {
        TextureManager.readPixels(x, y, width, height, format, type, address(pixels), bytes(pixels));
    }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, DoubleBuffer pixels) {
        GLUnsupported.call("glReadPixels");
    }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels) {
        TextureManager.readPixels(x, y, width, height, format, type, address(pixels), bytes(pixels));
    }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels) {
        TextureManager.readPixels(x, y, width, height, format, type, address(pixels), bytes(pixels));
    }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, ShortBuffer pixels) {
        TextureManager.readPixels(x, y, width, height, format, type, address(pixels), bytes(pixels));
    }

    // ---------------------------------------------------------------------------------------------------------
    // image specification and upload (compiled into display lists with a copy of the pixels)
    // ---------------------------------------------------------------------------------------------------------

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, long pixels_buffer_offset) {
        pullNative();
        GLUnsupported.call("glTexImage2D"); // pixel buffer objects are not supported
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
        pullNative();
        if (DisplayLists.isCompiling() && !recordTexImage(target, level, internalformat, width, height, border, format, type, addressSafe(pixels), bytes(pixels))) return;
        TextureManager.texImage2D(target, level, internalformat, width, height, border, format, type, addressSafe(pixels), bytes(pixels));
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, DoubleBuffer pixels) {
        pullNative();
        GLUnsupported.call("glTexImage2D");
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, FloatBuffer pixels) {
        pullNative();
        if (DisplayLists.isCompiling() && !recordTexImage(target, level, internalformat, width, height, border, format, type, addressSafe(pixels), bytes(pixels))) return;
        TextureManager.texImage2D(target, level, internalformat, width, height, border, format, type, addressSafe(pixels), bytes(pixels));
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, IntBuffer pixels) {
        pullNative();
        if (DisplayLists.isCompiling() && !recordTexImage(target, level, internalformat, width, height, border, format, type, addressSafe(pixels), bytes(pixels))) return;
        TextureManager.texImage2D(target, level, internalformat, width, height, border, format, type, addressSafe(pixels), bytes(pixels));
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ShortBuffer pixels) {
        pullNative();
        if (DisplayLists.isCompiling() && !recordTexImage(target, level, internalformat, width, height, border, format, type, addressSafe(pixels), bytes(pixels))) return;
        TextureManager.texImage2D(target, level, internalformat, width, height, border, format, type, addressSafe(pixels), bytes(pixels));
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, long pixels_buffer_offset) {
        pullNative();
        GLUnsupported.call("glTexSubImage2D"); // pixel buffer objects are not supported
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) {
        pullNative();
        if (DisplayLists.isCompiling() && !recordTexSubImage(target, level, xoffset, yoffset, width, height, format, type, addressSafe(pixels), bytes(pixels))) return;
        TextureManager.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, addressSafe(pixels), bytes(pixels));
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, DoubleBuffer pixels) {
        pullNative();
        GLUnsupported.call("glTexSubImage2D");
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, FloatBuffer pixels) {
        pullNative();
        if (DisplayLists.isCompiling() && !recordTexSubImage(target, level, xoffset, yoffset, width, height, format, type, addressSafe(pixels), bytes(pixels))) return;
        TextureManager.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, addressSafe(pixels), bytes(pixels));
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels) {
        pullNative();
        if (DisplayLists.isCompiling() && !recordTexSubImage(target, level, xoffset, yoffset, width, height, format, type, addressSafe(pixels), bytes(pixels))) return;
        TextureManager.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, addressSafe(pixels), bytes(pixels));
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ShortBuffer pixels) {
        pullNative();
        if (DisplayLists.isCompiling() && !recordTexSubImage(target, level, xoffset, yoffset, width, height, format, type, addressSafe(pixels), bytes(pixels))) return;
        TextureManager.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, addressSafe(pixels), bytes(pixels));
    }

    // ---------------------------------------------------------------------------------------------------------
    // texture parameters
    // ---------------------------------------------------------------------------------------------------------

    public static void glTexParameter(int target, int pname, FloatBuffer param) {
        pullNative();
        needRemaining(param, 1);
        if (pname == GL11.GL_TEXTURE_BORDER_COLOR && param.remaining() >= 4) {
            int p = param.position();
            float r = param.get(p);
            float g = param.get(p + 1);
            float b = param.get(p + 2);
            float a = param.get(p + 3);
            if (DisplayLists.isCompiling() && !DisplayLists.record(() -> TextureManager.texParameterBorderColor(target, r, g, b, a))) return;
            TextureManager.texParameterBorderColor(target, r, g, b, a);
            return;
        }
        glTexParameterf(target, pname, param.get(param.position()));
    }

    public static void glTexParameter(int target, int pname, IntBuffer param) {
        pullNative();
        needRemaining(param, 1);
        glTexParameteri(target, pname, param.get(param.position()));
    }

    public static void glTexParameterf(int target, int pname, float param) {
        pullNative();
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> TextureManager.texParameterf(target, pname, param))) return;
        TextureManager.texParameterf(target, pname, param);
    }

    public static void glTexParameteri(int target, int pname, int param) {
        pullNative();
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> TextureManager.texParameteri(target, pname, param))) return;
        TextureManager.texParameteri(target, pname, param);
    }

    // ---------------------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------------------

    private static final int[] UNPACK_PNAMES = {GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS,
            GL11.GL_UNPACK_SKIP_PIXELS};

    /** The unpack pixel store state (alignment, row length, skip rows, skip pixels). */
    private static int[] unpackState() {
        int[] state = new int[UNPACK_PNAMES.length];
        IntBuffer one = IntBuffer.allocate(1);
        for (int i = 0; i < state.length; i++) {
            TextureManager.getIntegerv(UNPACK_PNAMES[i], one);
            state[i] = one.get(0);
        }
        return state;
    }

    private static void applyUnpackState(int[] state) {
        for (int i = 0; i < state.length; i++) {
            TextureManager.setPixelStorei(UNPACK_PNAMES[i], state[i]);
        }
    }

    /** A private copy of the client pixels (null for a null pointer); null too when the data is too large to keep. */
    private static ByteBuffer copyPixels(long address, long bytes) {
        if (address == 0L || bytes <= 0L || bytes > Integer.MAX_VALUE) {
            return null;
        }
        ByteBuffer copy = ByteBuffer.allocateDirect((int) bytes);
        MemoryUtil.memCopy(address, MemoryUtil.memAddress(copy), bytes);
        return copy;
    }

    /**
     * glTexImage2D inside glNewList: records the upload with a copy of the pixels and the unpack state of this call. Returns true when
     * the caller must also execute the call now (GL_COMPILE_AND_EXECUTE).
     */
    private static boolean recordTexImage(int target, int level, int internalformat, int width, int height, int border, int format,
                                          int type, long address, long bytes) {
        ByteBuffer copy = copyPixels(address, bytes);
        if (address != 0L && copy == null) {
            GLUnsupported.call("glTexImage2D inside a display list with " + bytes + " bytes of pixels (executed immediately)");
            return true;
        }
        int[] store = unpackState();
        return DisplayLists.record(() -> {
            int[] saved = unpackState();
            applyUnpackState(store);
            try {
                TextureManager.texImage2D(target, level, internalformat, width, height, border, format, type,
                        copy == null ? 0L : MemoryUtil.memAddress(copy), copy == null ? 0L : copy.capacity());
            } finally {
                applyUnpackState(saved);
            }
        });
    }

    /** glTexSubImage2D inside glNewList, see {@link #recordTexImage}. */
    private static boolean recordTexSubImage(int target, int level, int xoffset, int yoffset, int width, int height, int format,
                                             int type, long address, long bytes) {
        ByteBuffer copy = copyPixels(address, bytes);
        if (address != 0L && copy == null) {
            GLUnsupported.call("glTexSubImage2D inside a display list with " + bytes + " bytes of pixels (executed immediately)");
            return true;
        }
        int[] store = unpackState();
        return DisplayLists.record(() -> {
            int[] saved = unpackState();
            applyUnpackState(store);
            try {
                TextureManager.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type,
                        copy == null ? 0L : MemoryUtil.memAddress(copy), copy == null ? 0L : copy.capacity());
            } finally {
                applyUnpackState(saved);
            }
        });
    }

    private static void needRemaining(Buffer b, int n) {
        if (b.remaining() < n) {
            throw new IllegalArgumentException("Number of remaining buffer elements is " + b.remaining() + ", must be at least " + n);
        }
    }

    private static void checkDirect(Buffer b) {
        if (!b.isDirect()) {
            throw new IllegalArgumentException("Non-direct buffer");
        }
    }

    // native address of the first element (the buffer position is included) and the readable / writable bytes from there

    private static long address(ByteBuffer b) {
        checkDirect(b);
        return MemoryUtil.memAddress(b);
    }

    private static long address(ShortBuffer b) {
        checkDirect(b);
        return MemoryUtil.memAddress(b);
    }

    private static long address(IntBuffer b) {
        checkDirect(b);
        return MemoryUtil.memAddress(b);
    }

    private static long address(FloatBuffer b) {
        checkDirect(b);
        return MemoryUtil.memAddress(b);
    }

    /** Like {@link #address} but a null buffer (a null pointer for GL) gives 0. */
    private static long addressSafe(ByteBuffer b) {
        return b == null ? 0L : address(b);
    }

    private static long addressSafe(ShortBuffer b) {
        return b == null ? 0L : address(b);
    }

    private static long addressSafe(IntBuffer b) {
        return b == null ? 0L : address(b);
    }

    private static long addressSafe(FloatBuffer b) {
        return b == null ? 0L : address(b);
    }

    private static long bytes(ByteBuffer b) {
        return b == null ? 0L : b.remaining();
    }

    private static long bytes(ShortBuffer b) {
        return b == null ? 0L : (long) b.remaining() << 1;
    }

    private static long bytes(IntBuffer b) {
        return b == null ? 0L : (long) b.remaining() << 2;
    }

    private static long bytes(FloatBuffer b) {
        return b == null ? 0L : (long) b.remaining() << 2;
    }
}
