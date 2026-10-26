package net.xiaoyu233.fml.vulkan.texture;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.core.StreamSlice;
import net.xiaoyu233.fml.vulkan.gl.GLErrors;
import net.xiaoyu233.fml.vulkan.gl.GLUnsupported;
import net.xiaoyu233.fml.vulkan.gl.state.GLState;
import net.xiaoyu233.fml.vulkan.texture.PixelFormatConverter.PixelStore;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * GL texture objects over VkImage (module B). Signatures of the public methods up to {@link #restoreBindings} are the binding
 * contract of ../docs/ARCHITECTURE.md (outside the repo) section 5.8; the remaining public methods are the entry points used by
 * {@code GLTexImpl}. The active texture unit is read from {@code GLState.get().activeTexture()}. Render thread only.
 *
 * <p>Image model: every texture is one VkImage in B8G8R8A8_UNORM whose levels all rest in SHADER_READ_ONLY_OPTIMAL between
 * operations. Rows are stored in GL memory order (row 0 = first row uploaded). Writes are recorded on the main command buffer
 * (after {@code ensureNoRenderPass}) when the texture was already used by the main command buffer in this frame, otherwise on
 * the upload command buffer, which executes before the main one. GL_GENERATE_MIPMAP chains of the latter are rebuilt lazily when
 * the texture is next sampled ({@link #afterLevel0Write}). Everything that has to outlive in-flight frames (images, views,
 * descriptor sets) is released through {@code RenderCore.deferDestroy}; samplers are shared and owned by {@link TextureSamplers}.
 */
public final class TextureManager {
    private static final int MAX_UNITS = 32;
    /** Value reported for GL_MAX_TEXTURE_UNITS (fixed function units; the backend only samples units 0 and 1). */
    private static final int REPORTED_TEXTURE_UNITS = 8;
    private static final int MAX_TEXTURE_SIZE = 16384;
    /** floor(log2(MAX_TEXTURE_SIZE)) + 1. */
    private static final int MAX_LEVELS = 15;
    private static final int DENSE_LIMIT = 1 << 20;
    private static final int READ_SCRATCH_KEEP = 1 << 20;

    // texture names: a dense table for the usual small names, a map for absurdly large explicit names
    private static TextureObject[] dense = new TextureObject[1024];
    private static final HashMap<Integer, TextureObject> sparse = new HashMap<>();
    private static int nextName = 1;
    private static final int[] bound = new int[MAX_UNITS];
    private static TextureObject defaultTexture;

    private static TextureObject white;
    private static long whiteSet;

    private static final PixelStore unpack = new PixelStore();
    private static final PixelStore pack = new PixelStore();

    // GL_PROXY_TEXTURE_2D answers
    private static final int[] proxyWidth = new int[MAX_LEVELS];
    private static final int[] proxyHeight = new int[MAX_LEVELS];
    private static int proxyInternalFormat = 1;

    // host readback (glGetTexImage) buffer and glReadPixels scratch
    private static long readbackBuffer;
    private static long readbackAllocation;
    private static long readbackAddress;
    private static long readbackSize;
    private static ByteBuffer readScratch;

    // result of the last stagingAlloc call (avoids a per-upload allocation)
    private static long stagingBuffer;
    private static long stagingOffset;
    private static long stagingAddress;

    private static final Set<String> WARNED = new HashSet<>();
    private static boolean initialized;

    private TextureManager() {
    }

    // =========================================================================================================
    // contract (ARCHITECTURE 5.8)
    // =========================================================================================================

    public static void init() {
        resetState();
        TextureDescriptors.init();
        TextureSamplers.init();

        defaultTexture = new TextureObject(0);
        defaultTexture.created = true;

        // 1x1 opaque white texture: what an unbound / incomplete texture unit samples
        white = new TextureObject(-1);
        white.created = true;
        white.params.minFilter = GL11.GL_NEAREST;
        white.params.magFilter = GL11.GL_NEAREST;
        white.params.wrapS = GL12.GL_CLAMP_TO_EDGE;
        white.params.wrapT = GL12.GL_CLAMP_TO_EDGE;
        if (!allocateImage(white, 1, 1, 1)) {
            throw new IllegalStateException("Failed to create the white fallback texture");
        }
        white.definedMask = 1;
        VkCommandBuffer cb = RenderCore.uploadCommandBuffer();
        TextureCommands.barrier(cb, white.image, 0, 1, VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        TextureCommands.clearColor(cb, white.image, 0, 1, 1f, 1f, 1f, 1f);
        TextureCommands.barrier(cb, white.image, 0, 1, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        resolveDescriptor(white);
        whiteSet = white.currentSet;
        if (whiteSet == 0L) {
            throw new IllegalStateException("Failed to create the descriptor set of the white fallback texture");
        }
        initialized = true;
        VkLog.LOGGER.info("TextureManager initialized (max texture size {})", maxTextureSize());
    }

    /** Destroys every texture and cache synchronously; the device must be idle (VulkanBackend.teardown guarantees it). */
    public static void shutdown() {
        if (!initialized) {
            return;
        }
        initialized = false;
        for (TextureObject t : dense) {
            if (t != null) {
                destroyNow(t);
            }
        }
        for (TextureObject t : sparse.values()) {
            destroyNow(t);
        }
        if (defaultTexture != null) {
            destroyNow(defaultTexture);
        }
        if (white != null) {
            destroyNow(white);
        }
        destroyReadback();
        freeReadScratch();
        TextureSamplers.shutdown();
        TextureDescriptors.shutdown();
        resetState();
        VkLog.LOGGER.info("TextureManager shut down");
    }

    private static void resetState() {
        dense = new TextureObject[1024];
        sparse.clear();
        nextName = 1;
        Arrays.fill(bound, 0);
        defaultTexture = null;
        white = null;
        whiteSet = 0L;
        resetStore(unpack);
        resetStore(pack);
        Arrays.fill(proxyWidth, 0);
        Arrays.fill(proxyHeight, 0);
        proxyInternalFormat = 1;
        WARNED.clear();
    }

    private static void resetStore(PixelStore ps) {
        ps.alignment = 4;
        ps.rowLength = 0;
        ps.skipRows = 0;
        ps.skipPixels = 0;
    }

    /** GL_TEXTURE_2D binding of {@code unit} (white 1x1 if 0 / unknown / without an image); marks the texture used in this frame. */
    public static long descriptorSetForUnit(int unit) {
        if (unit < 0 || unit >= MAX_UNITS) {
            return whiteSet;
        }
        TextureObject tex = lookup(bound[unit]);
        if (tex == null || tex.image == 0L) {
            return whiteSet;
        }
        if (tex.mipsStale) {
            settleMips(tex); // GL_GENERATE_MIPMAP: once per sampled texture, on the upload command buffer (never ends the render pass)
        }
        if (tex.setDirty) {
            resolveDescriptor(tex);
        }
        long set = tex.currentSet;
        if (set == 0L) {
            return whiteSet;
        }
        tex.lastUsedFrame = RenderCore.frameNumber();
        return set;
    }

    /** Draw trace ({@code fml.vulkan.traceFrame}): the texture bound to {@code unit} and what a draw would really sample. */
    public static String describeUnit(int unit) {
        if (unit < 0 || unit >= MAX_UNITS) {
            return "unit" + unit + "=invalid";
        }
        TextureObject t = lookup(bound[unit]);
        if (t == null) {
            return "unit" + unit + "=#" + bound[unit] + "(none: white)";
        }
        return "unit" + unit + "=#" + t.name + "(" + (t.image == 0L ? "no image: white" : t.width + "x" + t.height + " levels=" + t.mipLevels
                + " defined=0x" + Integer.toHexString(t.definedMask)) + " min=0x" + Integer.toHexString(t.params.minFilter) + " mag=0x"
                + Integer.toHexString(t.params.magFilter) + " wrap=0x" + Integer.toHexString(t.params.wrapS) + "/0x"
                + Integer.toHexString(t.params.wrapT) + " fmt=0x" + Integer.toHexString(t.internalFormat) + " cls=" + t.formatClass
                + (t.copyOpaque ? " OPAQUE-VIEW" : "") + " set=0x" + Long.toHexString(t.currentSet) + ")";
    }

    public static int boundTexture(int unit) {
        return unit < 0 || unit >= MAX_UNITS ? 0 : bound[unit];
    }

    /** Pixel store state, texture binding and limits; false if {@code pname} is not owned by the texture module. */
    public static boolean getIntegerv(int pname, IntBuffer out) {
        int value;
        switch (pname) {
            case GL11.GL_TEXTURE_BINDING_2D -> value = boundTexture(GLState.get().activeTexture());
            case GL11.GL_MAX_TEXTURE_SIZE -> value = maxTextureSize();
            case GL13.GL_MAX_TEXTURE_UNITS -> value = REPORTED_TEXTURE_UNITS;
            case 0x8068, 0x806A, 0x8514 -> value = 0; // TEXTURE_BINDING_1D / _3D / _CUBE_MAP: unsupported targets
            case GL11.GL_UNPACK_ALIGNMENT -> value = unpack.alignment;
            case GL11.GL_UNPACK_ROW_LENGTH -> value = unpack.rowLength;
            case GL11.GL_UNPACK_SKIP_ROWS -> value = unpack.skipRows;
            case GL11.GL_UNPACK_SKIP_PIXELS -> value = unpack.skipPixels;
            case GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_UNPACK_LSB_FIRST, GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST -> value = 0;
            case GL11.GL_PACK_ALIGNMENT -> value = pack.alignment;
            case GL11.GL_PACK_ROW_LENGTH -> value = pack.rowLength;
            case GL11.GL_PACK_SKIP_ROWS -> value = pack.skipRows;
            case GL11.GL_PACK_SKIP_PIXELS -> value = pack.skipPixels;
            default -> {
                return false;
            }
        }
        out.put(out.position(), value);
        return true;
    }

    /** glPushAttrib(GL_TEXTURE_BIT): the bindings and the texture parameters of the bound textures. */
    public static Object saveBindings() {
        SavedBindings saved = new SavedBindings();
        for (int u = 0; u < MAX_UNITS; u++) {
            saved.names[u] = bound[u];
            TextureObject t = lookup(bound[u]);
            if (t != null) {
                saved.params[u] = new TextureObject.Params();
                saved.params[u].copyFrom(t.params);
            }
        }
        return saved;
    }

    public static void restoreBindings(Object savedState) {
        if (!(savedState instanceof SavedBindings saved)) {
            return;
        }
        for (int u = 0; u < MAX_UNITS; u++) {
            TextureObject t = lookup(saved.names[u]);
            // a texture deleted in between is unbound (GL: the binding reverts to 0)
            bound[u] = t != null || saved.names[u] == 0 ? saved.names[u] : 0;
            if (t != null && saved.params[u] != null) {
                if (!saved.params[u].generateMipmap) {
                    settleMips(t); // see texParameteri(GL_GENERATE_MIPMAP)
                }
                t.params.copyFrom(saved.params[u]);
                t.setDirty = true;
            }
        }
    }

    private static final class SavedBindings {
        final int[] names = new int[MAX_UNITS];
        final TextureObject.Params[] params = new TextureObject.Params[MAX_UNITS];
    }

    // =========================================================================================================
    // names, binding, pixel store (called by GLTexImpl)
    // =========================================================================================================

    public static void setPixelStorei(int pname, int param) {
        switch (pname) {
            case GL11.GL_UNPACK_ALIGNMENT -> setAlignment(unpack, param);
            case GL11.GL_UNPACK_ROW_LENGTH -> unpack.rowLength = nonNegative(param, unpack.rowLength);
            case GL11.GL_UNPACK_SKIP_ROWS -> unpack.skipRows = nonNegative(param, unpack.skipRows);
            case GL11.GL_UNPACK_SKIP_PIXELS -> unpack.skipPixels = nonNegative(param, unpack.skipPixels);
            case GL11.GL_PACK_ALIGNMENT -> setAlignment(pack, param);
            case GL11.GL_PACK_ROW_LENGTH -> pack.rowLength = nonNegative(param, pack.rowLength);
            case GL11.GL_PACK_SKIP_ROWS -> pack.skipRows = nonNegative(param, pack.skipRows);
            case GL11.GL_PACK_SKIP_PIXELS -> pack.skipPixels = nonNegative(param, pack.skipPixels);
            case GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_UNPACK_LSB_FIRST, GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST -> {
                if (param != 0) {
                    GLUnsupported.call("glPixelStore(SWAP_BYTES/LSB_FIRST)");
                }
            }
            default -> {
                // 3D / PBO related pixel store parameters: nothing to do for 2D textures
            }
        }
    }

    private static void setAlignment(PixelStore ps, int value) {
        if (value == 1 || value == 2 || value == 4 || value == 8) {
            ps.alignment = value;
        } else {
            GLErrors.set(GL11.GL_INVALID_VALUE);
        }
    }

    private static int nonNegative(int value, int old) {
        if (value < 0) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return old;
        }
        return value;
    }

    public static void bindTexture(int unit, int name) {
        if (unit < 0 || unit >= MAX_UNITS) {
            return;
        }
        if (name < 0) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        if (name != 0) {
            // the first bind creates the texture object (a name that was only generated is not a texture yet)
            TextureObject t = lookup(name);
            if (t == null) {
                t = new TextureObject(name);
                store(name, t);
            }
            t.created = true;
        }
        bound[unit] = name;
    }

    public static int genTextures() {
        while (nextName > 0 && lookup(nextName) != null) {
            nextName++;
        }
        int name = nextName++;
        store(name, new TextureObject(name));
        return name;
    }

    /** Writes {@code remaining()} names starting at the buffer position; the position is not changed (LWJGL 2). */
    public static void genTextures(IntBuffer names) {
        int p = names.position();
        int n = names.remaining();
        for (int i = 0; i < n; i++) {
            names.put(p + i, genTextures());
        }
    }

    public static void deleteTextures(int name) {
        if (name <= 0) {
            return;
        }
        TextureObject t = lookup(name);
        if (t == null) {
            return;
        }
        remove(name);
        for (int u = 0; u < MAX_UNITS; u++) {
            if (bound[u] == name) {
                bound[u] = 0;
            }
        }
        releaseImage(t);
    }

    /** Reads {@code remaining()} names from the buffer position; the position is not changed (LWJGL 2). */
    public static void deleteTextures(IntBuffer names) {
        int p = names.position();
        int n = names.remaining();
        for (int i = 0; i < n; i++) {
            deleteTextures(names.get(p + i));
        }
    }

    public static boolean isTexture(int name) {
        if (name <= 0) {
            return false;
        }
        TextureObject t = lookup(name);
        return t != null && t.created;
    }

    private static TextureObject lookup(int name) {
        if (name == 0) {
            return defaultTexture;
        }
        if (name < 0) {
            return null;
        }
        if (name < dense.length) {
            return dense[name];
        }
        return name < DENSE_LIMIT ? null : sparse.get(name);
    }

    private static void store(int name, TextureObject t) {
        if (name < DENSE_LIMIT) {
            if (name >= dense.length) {
                dense = Arrays.copyOf(dense, Math.max(name + 1, dense.length * 2));
            }
            dense[name] = t;
        } else {
            sparse.put(name, t);
        }
    }

    private static void remove(int name) {
        if (name < DENSE_LIMIT) {
            if (name < dense.length) {
                dense[name] = null;
            }
        } else {
            sparse.remove(name);
        }
    }

    // =========================================================================================================
    // image specification / upload
    // =========================================================================================================

    /**
     * glTexImage2D. {@code pixels == 0} means a null pointer (the level is allocated and cleared to zero); otherwise
     * {@code pixelBytes} is the number of readable bytes at {@code pixels}.
     */
    public static void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type,
                                  long pixels, long pixelBytes) {
        if (target == GL11.GL_PROXY_TEXTURE_2D) {
            proxyTexImage(level, internalFormat, width, height, border);
            return;
        }
        if (target != GL11.GL_TEXTURE_2D) {
            unsupportedTarget("glTexImage2D", target);
            return;
        }
        if (level < 0 || level >= MAX_LEVELS || width < 0 || height < 0 || border != 0) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        int cls = PixelFormatConverter.classifyInternalFormat(internalFormat);
        if (cls == PixelFormatConverter.CLASS_UNSUPPORTED) {
            GLUnsupported.call("glTexImage2D(depth/stencil internalformat)");
            return;
        }
        int bpp = 0;
        if (pixels != 0L) {
            bpp = checkTransfer("glTexImage2D", format, type);
            if (bpp <= 0) {
                return;
            }
        }
        int max = maxTextureSize();
        if (width > max || height > max) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        TextureObject tex = current2D();
        if (tex == null) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        long stride = 0L;
        long offset = 0L;
        if (pixels != 0L && width > 0 && height > 0) {
            checkAvailable(PixelFormatConverter.requiredBytes(width, height, bpp, unpack), pixelBytes);
            stride = PixelFormatConverter.rowStride(width, bpp, unpack);
            offset = PixelFormatConverter.startOffset(bpp, stride, unpack);
        }
        if (width == 0 || height == 0) {
            if (level == 0) {
                releaseImage(tex); // a zero sized level 0 leaves the texture without an image (incomplete)
            }
            return;
        }
        specifyLevel(tex, level, internalFormat, cls, width, height, format, type, pixels == 0L ? 0L : pixels + offset, stride, true);
    }

    /**
     * Defines level {@code level} of {@code tex}: (re)creates the image for level 0, grows the mip chain for deeper levels, then
     * uploads the pixels (srcRow0 != 0, tightly validated by the caller) or clears the level to zero.
     *
     * @param regenerate level 0 only: let GL_GENERATE_MIPMAP rebuild the chain behind the definition (false when the caller overwrites
     *                   the level right away and rebuilds it itself)
     * @return false when the level was not defined (unsupported, a size that does not fit level 0, out of memory); the caller must then
     * not touch the level
     */
    private static boolean specifyLevel(TextureObject tex, int level, int internalFormat, int cls, int width, int height, int format,
                                        int type, long srcRow0, long srcStride, boolean regenerate) {
        boolean newImage = false;
        if (level == 0) {
            int wantLevels = initialLevels(tex.params, width, height);
            boolean reuse = tex.hasImage() && tex.width == width && tex.height == height && tex.mipLevels >= wantLevels;
            if (!reuse) {
                releaseImage(tex);
                if (!allocateImage(tex, width, height, wantLevels)) {
                    return false;
                }
                newImage = true;
            }
            tex.internalFormat = internalFormat;
            tex.formatClass = cls;
            tex.copyOpaque = false;
            tex.definedMask = newImage ? 1 : tex.definedMask | 1;
        } else {
            if (!tex.hasImage()) {
                GLUnsupported.call("glTexImage2D(mip level before level 0)");
                return false;
            }
            if (width != levelDim(tex.width, level) || height != levelDim(tex.height, level)) {
                warnOnce("level-size", "glTexImage2D / glCopyTexImage2D: level " + level + " has a size that does not match level 0 ("
                    + tex.width + "x" + tex.height + "), ignored");
                return false;
            }
            if (level >= fullLevels(tex.width, tex.height)) {
                // GL accepts levels past the complete chain of level 0 (8x8: level 4 as 1x1), they just never take part in sampling
                return false;
            }
            settleMips(tex); // the explicit data must not be overwritten by a regeneration that is still pending
            if (level >= tex.mipLevels && !growMipChain(tex, Math.max(level + 1, growTarget(tex, level)), false)) {
                return false;
            }
            tex.definedMask |= 1 << level;
        }
        tex.setDirty = true;
        recordDefine(tex, level, newImage, width, height, format, type, srcRow0, srcStride);
        if (level == 0 && regenerate) {
            afterLevel0Write(tex);
        }
        return true;
    }

    /** Records the upload of a whole level (or its zero clear) right after the image was created / the level was defined. */
    private static void recordDefine(TextureObject tex, int level, boolean newImage, int w, int h, int format, int type, long srcRow0,
                                     long srcStride) {
        boolean hasData = srcRow0 != 0L;
        if (hasData) {
            stagingAlloc((long) w * h * 4);
            if (!PixelFormatConverter.toBgra8(srcRow0, srcStride, w, h, format, type, tex.formatClass, stagingAddress)) {
                hasData = false; // cannot happen: the format / type pair was validated
            }
        }
        float clearAlpha = PixelFormatConverter.hasAlpha(tex.formatClass) ? 0f : 1f;
        long image = tex.image;
        int levels = tex.mipLevels;
        if (newImage) {
            // brand new image: nothing in flight can use it, the upload command buffer is always fine
            VkCommandBuffer cb = RenderCore.uploadCommandBuffer();
            TextureCommands.barrier(cb, image, 0, levels, VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            if (hasData) {
                TextureCommands.copyBufferToImage(cb, stagingBuffer, stagingOffset, image, 0, 0, 0, w, h);
                if (levels > 1) {
                    TextureCommands.clearColor(cb, image, 1, levels - 1, 0f, 0f, 0f, clearAlpha);
                }
            } else {
                TextureCommands.clearColor(cb, image, 0, levels, 0f, 0f, 0f, clearAlpha);
            }
            TextureCommands.barrier(cb, image, 0, levels, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        } else {
            VkCommandBuffer cb = commandBufferFor(tex);
            TextureCommands.barrier(cb, image, level, 1, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            if (hasData) {
                TextureCommands.copyBufferToImage(cb, stagingBuffer, stagingOffset, image, level, 0, 0, w, h);
            } else {
                TextureCommands.clearColor(cb, image, level, 1, 0f, 0f, 0f, clearAlpha);
            }
            TextureCommands.barrier(cb, image, level, 1, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    /** glTexSubImage2D (see {@link #texImage2D} for the pixel pointer convention; a null pointer is a no-op). */
    public static void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type,
                                     long pixels, long pixelBytes) {
        if (target == GL11.GL_PROXY_TEXTURE_2D) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        if (target != GL11.GL_TEXTURE_2D) {
            unsupportedTarget("glTexSubImage2D", target);
            return;
        }
        if (level < 0 || level >= MAX_LEVELS || width < 0 || height < 0 || xoffset < 0 || yoffset < 0) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        int bpp = checkTransfer("glTexSubImage2D", format, type);
        if (bpp <= 0) {
            return;
        }
        TextureObject tex = current2D();
        if (tex != null && level > 0) {
            // a pending GL_GENERATE_MIPMAP regeneration defines the deeper levels, and must not overwrite the data written here later
            settleMips(tex);
        }
        if (tex == null || !tex.hasImage() || level >= tex.mipLevels || !tex.isDefined(level)) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        if ((long) xoffset + width > levelDim(tex.width, level) || (long) yoffset + height > levelDim(tex.height, level)) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        if (width == 0 || height == 0 || pixels == 0L) {
            return;
        }
        checkAvailable(PixelFormatConverter.requiredBytes(width, height, bpp, unpack), pixelBytes);
        long stride = PixelFormatConverter.rowStride(width, bpp, unpack);
        long row0 = pixels + PixelFormatConverter.startOffset(bpp, stride, unpack);

        stagingAlloc((long) width * height * 4);
        if (!PixelFormatConverter.toBgra8(row0, stride, width, height, format, type, tex.formatClass, stagingAddress)) {
            return;
        }
        VkCommandBuffer cb = commandBufferFor(tex);
        TextureCommands.barrier(cb, tex.image, level, 1, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        TextureCommands.copyBufferToImage(cb, stagingBuffer, stagingOffset, tex.image, level, xoffset, yoffset, width, height);
        TextureCommands.barrier(cb, tex.image, level, 1, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        if (level == 0) {
            afterLevel0Write(tex);
        }
    }

    public static void copyTexImage2D(int target, int level, int internalFormat, int x, int y, int width, int height, int border) {
        if (target == GL11.GL_PROXY_TEXTURE_2D) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        if (target != GL11.GL_TEXTURE_2D) {
            unsupportedTarget("glCopyTexImage2D", target);
            return;
        }
        if (level < 0 || level >= MAX_LEVELS || width < 0 || height < 0 || border != 0) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        int cls = PixelFormatConverter.classifyInternalFormat(internalFormat);
        if (cls == PixelFormatConverter.CLASS_UNSUPPORTED) {
            GLUnsupported.call("glCopyTexImage2D(depth/stencil internalformat)");
            return;
        }
        int max = maxTextureSize();
        if (width > max || height > max) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        TextureObject tex = current2D();
        if (tex == null) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        if (width == 0 || height == 0) {
            if (level == 0) {
                releaseImage(tex);
            }
            return;
        }
        // define the level (zero filled), then copy the framebuffer rectangle into it
        if (specifyLevel(tex, level, internalFormat, cls, width, height, 0, 0, 0L, 0L, false)) {
            copyFromFramebuffer(tex, level, 0, 0, x, y, width, height);
            if (level == 0) {
                setCopyOpaque(tex);
                afterLevel0Write(tex);
            }
        }
    }

    public static void copyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        if (target == GL11.GL_PROXY_TEXTURE_2D) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        if (target != GL11.GL_TEXTURE_2D) {
            unsupportedTarget("glCopyTexSubImage2D", target);
            return;
        }
        if (level < 0 || level >= MAX_LEVELS || width < 0 || height < 0 || xoffset < 0 || yoffset < 0) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        TextureObject tex = current2D();
        if (tex != null && level > 0) {
            settleMips(tex); // see texSubImage2D
        }
        if (tex == null || !tex.hasImage() || level >= tex.mipLevels || !tex.isDefined(level)) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        int lw = levelDim(tex.width, level);
        int lh = levelDim(tex.height, level);
        if ((long) xoffset + width > lw || (long) yoffset + height > lh) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        if (width == 0 || height == 0) {
            return;
        }
        boolean copied = copyFromFramebuffer(tex, level, xoffset, yoffset, x, y, width, height);
        if (level == 0) {
            if (xoffset == 0 && yoffset == 0 && width == lw && height == lh) {
                setCopyOpaque(tex);
            }
            if (copied) {
                afterLevel0Write(tex);
            }
        }
    }

    /**
     * Copies a framebuffer rectangle (GL coordinates, bottom-left origin) into a texture level on the main command buffer.
     * The source rectangle is clipped against the framebuffer (GL leaves the pixels outside undefined) and the destination moves
     * with it, so vkCmdBlitImage never reads outside the offscreen image. Rebuilding the mip chain behind a level 0 copy is up to the
     * caller ({@link #afterLevel0Write}).
     *
     * @return false when nothing was copied (the clipped rectangle is empty)
     */
    private static boolean copyFromFramebuffer(TextureObject tex, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        long fbW = RenderCore.framebufferWidth();
        long fbH = RenderCore.framebufferHeight();
        long x0 = Math.max((long) x, 0L);
        long y0 = Math.max((long) y, 0L);
        long x1 = Math.min((long) x + width, fbW);
        long y1 = Math.min((long) y + height, fbH);
        if (x0 >= x1 || y0 >= y1) {
            return false;
        }
        int dstX = (int) (xoffset + (x0 - x));
        int dstY = (int) (yoffset + (y0 - y));
        settleMips(tex); // this write goes to the main command buffer: nothing may be left pending on the upload one
        RenderCore.ensureNoRenderPass();
        VkCommandBuffer cb = RenderCore.mainCommandBuffer();
        TextureCommands.barrier(cb, tex.image, level, 1, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        RenderCore.blitColorToImage(tex.image, level, dstX, dstY, (int) x0, (int) y0, (int) (x1 - x0), (int) (y1 - y0));
        TextureCommands.barrier(cb, tex.image, level, 1, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        tex.lastUsedFrame = RenderCore.frameNumber(); // the main command buffer now holds a write to this texture
        return true;
    }

    /**
     * The GL window framebuffer MITE creates has no alpha bits, so a copy of it reads alpha 1. The offscreen target is RGBA, so the
     * view sampling the texture maps alpha to ONE when the device supports view swizzles.
     */
    private static void setCopyOpaque(TextureObject tex) {
        if (!tex.copyOpaque) {
            tex.copyOpaque = true;
            if (TextureSamplers.viewSwizzleSupported()) {
                tex.setDirty = true;
            } else if (WARNED.add("no-view-swizzle")) {
                // a portability subset device without imageViewFormatSwizzle; the framebuffer alpha is 1 unless blending wrote it
                VkLog.LOGGER.debug("Image view swizzles are not available: textures copied from the framebuffer keep the framebuffer "
                    + "alpha instead of alpha 1");
            }
        }
    }

    public static void generateMipmap(int target) {
        if (target != GL11.GL_TEXTURE_2D) {
            if (target == GL11.GL_PROXY_TEXTURE_2D) {
                GLErrors.set(GL11.GL_INVALID_ENUM);
            } else {
                unsupportedTarget("glGenerateMipmap", target);
            }
            return;
        }
        TextureObject tex = current2D();
        if (tex == null || !tex.hasImage()) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        generateLevels(tex, false);
    }

    /**
     * GL_GENERATE_MIPMAP: the chain follows every write to level 0. A write recorded on the main command buffer (the texture was already
     * sampled in this frame) is followed by the regeneration right away. A write recorded on the upload command buffer only marks the
     * chain stale: nothing reads it before the texture is next sampled, and an atlas that gets many sprite uploads per frame then costs
     * one regeneration per frame ({@link #settleMips} in descriptorSetForUnit) instead of one per upload.
     *
     * <p>Invariant: a stale texture was not used by the main command buffer in the current frame (staleness is only set on the upload
     * command buffer branch, and every path that marks a texture used or writes it on the main command buffer settles it first), so
     * the regeneration can always be recorded on the upload command buffer, in front of everything the main one does.
     */
    private static void afterLevel0Write(TextureObject tex) {
        if (!tex.params.generateMipmap) {
            return;
        }
        if (tex.lastUsedFrame == RenderCore.frameNumber()) {
            generateLevels(tex, false);
        } else {
            tex.mipsStale = true;
        }
    }

    /** Regenerates a stale mip chain now (on the upload command buffer); a no-op for a texture that is up to date. */
    private static void settleMips(TextureObject tex) {
        if (tex.mipsStale) {
            generateLevels(tex, true);
        }
    }

    /**
     * Generates the levels above level 0 (growing the image to the needed number of levels first).
     *
     * @param uploadOnly record on the upload command buffer even if the texture was used by the main one in this frame (only valid
     *                   when no write to the texture was recorded on the main command buffer after the level 0 data being mipped)
     */
    private static void generateLevels(TextureObject tex, boolean uploadOnly) {
        tex.mipsStale = false;
        int levels = fullLevels(tex.width, tex.height);
        if (tex.params.maxLevelSet) {
            levels = Math.max(1, Math.min(levels, tex.params.maxLevel + 1));
        }
        if (levels <= 1) {
            return;
        }
        if (tex.mipLevels < levels && !growMipChain(tex, levels, uploadOnly)) {
            return;
        }
        VkCommandBuffer cb = commandBufferFor(tex, uploadOnly);
        TextureCommands.generateMipmaps(cb, tex.image, tex.mipLevels, tex.width, tex.height);
        tex.definedMask |= (1 << tex.mipLevels) - 1;
        tex.setDirty = true;
    }

    // =========================================================================================================
    // texture parameters
    // =========================================================================================================

    public static void texParameteri(int target, int pname, int param) {
        TextureObject tex = parameterTarget("glTexParameteri", target);
        if (tex == null) {
            return;
        }
        TextureObject.Params p = tex.params;
        switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> {
                if (!isMinFilter(param)) {
                    GLErrors.set(GL11.GL_INVALID_ENUM);
                } else if (p.minFilter != param) {
                    p.minFilter = param;
                    tex.setDirty = true;
                }
            }
            case GL11.GL_TEXTURE_MAG_FILTER -> {
                if (param != GL11.GL_NEAREST && param != GL11.GL_LINEAR) {
                    GLErrors.set(GL11.GL_INVALID_ENUM);
                } else if (p.magFilter != param) {
                    p.magFilter = param;
                    tex.setDirty = true;
                }
            }
            case GL11.GL_TEXTURE_WRAP_S -> {
                if (!isWrapMode(param)) {
                    GLErrors.set(GL11.GL_INVALID_ENUM);
                } else if (p.wrapS != param) {
                    p.wrapS = param;
                    tex.setDirty = true;
                }
            }
            case GL11.GL_TEXTURE_WRAP_T -> {
                if (!isWrapMode(param)) {
                    GLErrors.set(GL11.GL_INVALID_ENUM);
                } else if (p.wrapT != param) {
                    p.wrapT = param;
                    tex.setDirty = true;
                }
            }
            case GL12.GL_TEXTURE_WRAP_R -> {
                if (!isWrapMode(param)) {
                    GLErrors.set(GL11.GL_INVALID_ENUM);
                } else {
                    p.wrapR = param; // no effect on 2D textures
                }
            }
            case GL12.GL_TEXTURE_BASE_LEVEL -> {
                if (param < 0) {
                    GLErrors.set(GL11.GL_INVALID_VALUE);
                } else if (p.baseLevel != param) {
                    p.baseLevel = param;
                    tex.setDirty = true;
                }
            }
            case GL12.GL_TEXTURE_MAX_LEVEL -> {
                if (param < 0) {
                    GLErrors.set(GL11.GL_INVALID_VALUE);
                } else {
                    p.maxLevelSet = true; // clamped to the allocated levels only when the view / sampler is built
                    if (p.maxLevel != param) {
                        p.maxLevel = param;
                        tex.setDirty = true;
                    }
                }
            }
            case GL12.GL_TEXTURE_MIN_LOD -> setMinLod(tex, param);
            case GL12.GL_TEXTURE_MAX_LOD -> setMaxLod(tex, param);
            case GL14.GL_TEXTURE_LOD_BIAS -> setLodBias(tex, param);
            case GL14.GL_GENERATE_MIPMAP -> {
                if (param == 0 && p.generateMipmap) {
                    settleMips(tex); // a regeneration still pending belongs to the writes made while the flag was set
                }
                p.generateMipmap = param != 0;
            }
            case EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT -> setAnisotropy(tex, param);
            default -> ignoreParameter(pname);
        }
    }

    public static void texParameterf(int target, int pname, float param) {
        switch (pname) {
            case GL12.GL_TEXTURE_MIN_LOD, GL12.GL_TEXTURE_MAX_LOD, GL14.GL_TEXTURE_LOD_BIAS,
                 EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT -> {
                TextureObject tex = parameterTarget("glTexParameterf", target);
                if (tex == null) {
                    return;
                }
                switch (pname) {
                    case GL12.GL_TEXTURE_MIN_LOD -> setMinLod(tex, param);
                    case GL12.GL_TEXTURE_MAX_LOD -> setMaxLod(tex, param);
                    case GL14.GL_TEXTURE_LOD_BIAS -> setLodBias(tex, param);
                    default -> setAnisotropy(tex, param);
                }
            }
            default -> texParameteri(target, pname, (int) param); // enum / integer valued parameters passed as floats
        }
    }

    /** glTexParameterfv(GL_TEXTURE_BORDER_COLOR). */
    public static void texParameterBorderColor(int target, float r, float g, float b, float a) {
        TextureObject tex = parameterTarget("glTexParameter(GL_TEXTURE_BORDER_COLOR)", target);
        if (tex == null) {
            return;
        }
        float[] c = tex.params.borderColor;
        if (c[0] != r || c[1] != g || c[2] != b || c[3] != a) {
            c[0] = r;
            c[1] = g;
            c[2] = b;
            c[3] = a;
            tex.setDirty = true;
        }
    }

    private static void setMinLod(TextureObject tex, float v) {
        if (tex.params.minLod != v) {
            tex.params.minLod = v;
            tex.setDirty = true;
        }
    }

    private static void setMaxLod(TextureObject tex, float v) {
        if (tex.params.maxLod != v) {
            tex.params.maxLod = v;
            tex.setDirty = true;
        }
    }

    private static void setLodBias(TextureObject tex, float v) {
        if (tex.params.lodBias != v) {
            tex.params.lodBias = v;
            tex.setDirty = true;
        }
    }

    private static void setAnisotropy(TextureObject tex, float v) {
        if (v < 1f) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        if (tex.params.maxAnisotropy != v) {
            tex.params.maxAnisotropy = v; // clamped to the device maximum (or ignored without samplerAnisotropy) in TextureSamplers
            tex.setDirty = true;
        }
    }

    private static boolean isMinFilter(int v) {
        return v == GL11.GL_NEAREST || v == GL11.GL_LINEAR || v == GL11.GL_NEAREST_MIPMAP_NEAREST || v == GL11.GL_LINEAR_MIPMAP_NEAREST
            || v == GL11.GL_NEAREST_MIPMAP_LINEAR || v == GL11.GL_LINEAR_MIPMAP_LINEAR;
    }

    private static boolean isWrapMode(int v) {
        return v == GL11.GL_REPEAT || v == GL11.GL_CLAMP || v == GL12.GL_CLAMP_TO_EDGE || v == GL13.GL_CLAMP_TO_BORDER
            || v == GL14.GL_MIRRORED_REPEAT;
    }

    private static void ignoreParameter(int pname) {
        GLUnsupported.call("glTexParameter(pname 0x" + Integer.toHexString(pname) + ")");
    }

    /** The texture a glTexParameter / glGetTexParameter call applies to (null after reporting the problem). */
    private static TextureObject parameterTarget(String function, int target) {
        if (target != GL11.GL_TEXTURE_2D) {
            if (target == GL11.GL_PROXY_TEXTURE_2D) {
                GLErrors.set(GL11.GL_INVALID_ENUM);
            } else {
                unsupportedTarget(function, target);
            }
            return null;
        }
        TextureObject tex = current2D();
        if (tex == null) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
        }
        return tex;
    }

    public static int getTexParameteri(int target, int pname) {
        TextureObject tex = parameterTarget("glGetTexParameter", target);
        if (tex == null) {
            return 0;
        }
        TextureObject.Params p = tex.params;
        return switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> p.minFilter;
            case GL11.GL_TEXTURE_MAG_FILTER -> p.magFilter;
            case GL11.GL_TEXTURE_WRAP_S -> p.wrapS;
            case GL11.GL_TEXTURE_WRAP_T -> p.wrapT;
            case GL12.GL_TEXTURE_WRAP_R -> p.wrapR;
            case GL12.GL_TEXTURE_BASE_LEVEL -> p.baseLevel;
            case GL12.GL_TEXTURE_MAX_LEVEL -> p.maxLevel;
            case GL12.GL_TEXTURE_MIN_LOD -> Math.round(p.minLod);
            case GL12.GL_TEXTURE_MAX_LOD -> Math.round(p.maxLod);
            case GL14.GL_TEXTURE_LOD_BIAS -> Math.round(p.lodBias);
            case GL14.GL_GENERATE_MIPMAP -> p.generateMipmap ? 1 : 0;
            case EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT -> Math.round(p.maxAnisotropy);
            case GL11.GL_TEXTURE_BORDER_COLOR -> Math.round(p.borderColor[0]);
            case GL11.GL_TEXTURE_RESIDENT -> 1;
            case GL11.GL_TEXTURE_PRIORITY -> 1;
            default -> {
                GLErrors.set(GL11.GL_INVALID_ENUM);
                yield 0;
            }
        };
    }

    public static float getTexParameterf(int target, int pname) {
        switch (pname) {
            case GL12.GL_TEXTURE_MIN_LOD, GL12.GL_TEXTURE_MAX_LOD, GL14.GL_TEXTURE_LOD_BIAS,
                 EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, GL11.GL_TEXTURE_BORDER_COLOR, GL11.GL_TEXTURE_PRIORITY -> {
                TextureObject tex = parameterTarget("glGetTexParameter", target);
                if (tex == null) {
                    return 0f;
                }
                TextureObject.Params p = tex.params;
                return switch (pname) {
                    case GL12.GL_TEXTURE_MIN_LOD -> p.minLod;
                    case GL12.GL_TEXTURE_MAX_LOD -> p.maxLod;
                    case GL14.GL_TEXTURE_LOD_BIAS -> p.lodBias;
                    case EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT -> p.maxAnisotropy;
                    case GL11.GL_TEXTURE_BORDER_COLOR -> p.borderColor[0];
                    default -> 1f;
                };
            }
            default -> {
                return getTexParameteri(target, pname);
            }
        }
    }

    /** All four components of GL_TEXTURE_BORDER_COLOR. */
    public static void getTexBorderColor(int target, float[] out) {
        TextureObject tex = parameterTarget("glGetTexParameter", target);
        if (tex != null) {
            System.arraycopy(tex.params.borderColor, 0, out, 0, 4);
        }
    }

    public static int getTexLevelParameteri(int target, int level, int pname) {
        if (target == GL11.GL_PROXY_TEXTURE_2D) {
            return proxyLevelParameter(level, pname);
        }
        if (target != GL11.GL_TEXTURE_2D) {
            unsupportedTarget("glGetTexLevelParameter", target);
            return 0;
        }
        if (level < 0 || level >= MAX_LEVELS) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return 0;
        }
        TextureObject tex = current2D();
        if (tex == null) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return 0;
        }
        if (level > 0) {
            settleMips(tex); // a pending GL_GENERATE_MIPMAP regeneration defines (and may allocate) the deeper levels
        }
        boolean defined = tex.hasImage() && level < tex.mipLevels && tex.isDefined(level);
        int w = defined ? levelDim(tex.width, level) : 0;
        int h = defined ? levelDim(tex.height, level) : 0;
        return levelParameter(pname, defined, w, h, defined ? tex.internalFormat : 1, tex.formatClass);
    }

    private static int levelParameter(int pname, boolean defined, int w, int h, int internalFormat, int cls) {
        boolean rgba = cls == PixelFormatConverter.CLASS_RGBA;
        boolean rgb = rgba || cls == PixelFormatConverter.CLASS_RGB;
        boolean lum = cls == PixelFormatConverter.CLASS_LUMINANCE || cls == PixelFormatConverter.CLASS_LUMINANCE_ALPHA;
        int bits = 0;
        switch (pname) {
            case GL11.GL_TEXTURE_WIDTH:
                return w;
            case GL11.GL_TEXTURE_HEIGHT:
                return h;
            case GL12.GL_TEXTURE_DEPTH:
                return defined ? 1 : 0;
            case GL11.GL_TEXTURE_BORDER:
            case GL13.GL_TEXTURE_COMPRESSED:
            case GL14.GL_TEXTURE_DEPTH_SIZE:
                return 0;
            case GL11.GL_TEXTURE_INTERNAL_FORMAT:
                return internalFormat;
            case GL11.GL_TEXTURE_RED_SIZE:
                bits = rgb || cls == PixelFormatConverter.CLASS_RED || cls == PixelFormatConverter.CLASS_RG ? 8 : 0;
                break;
            case GL11.GL_TEXTURE_GREEN_SIZE:
                bits = rgb || cls == PixelFormatConverter.CLASS_RG ? 8 : 0;
                break;
            case GL11.GL_TEXTURE_BLUE_SIZE:
                bits = rgb ? 8 : 0;
                break;
            case GL11.GL_TEXTURE_ALPHA_SIZE:
                bits = rgba || cls == PixelFormatConverter.CLASS_LUMINANCE_ALPHA || cls == PixelFormatConverter.CLASS_ALPHA ? 8 : 0;
                break;
            case GL11.GL_TEXTURE_LUMINANCE_SIZE:
                bits = lum ? 8 : 0;
                break;
            case GL11.GL_TEXTURE_INTENSITY_SIZE:
                bits = cls == PixelFormatConverter.CLASS_INTENSITY ? 8 : 0;
                break;
            default:
                GLErrors.set(GL11.GL_INVALID_ENUM);
                return 0;
        }
        return defined ? bits : 0;
    }

    // GL_PROXY_TEXTURE_2D: only the validity of the requested size is recorded, no Vulkan object is ever created

    private static void proxyTexImage(int level, int internalFormat, int width, int height, int border) {
        if (level < 0 || level >= MAX_LEVELS) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        int max = maxTextureSize();
        boolean ok = width > 0 && height > 0 && width <= max && height <= max && border == 0
            && PixelFormatConverter.classifyInternalFormat(internalFormat) != PixelFormatConverter.CLASS_UNSUPPORTED;
        proxyWidth[level] = ok ? width : 0;
        proxyHeight[level] = ok ? height : 0;
        proxyInternalFormat = ok ? internalFormat : 0;
    }

    private static int proxyLevelParameter(int level, int pname) {
        if (level < 0 || level >= MAX_LEVELS) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return 0;
        }
        boolean ok = proxyWidth[level] != 0;
        int cls = PixelFormatConverter.classifyInternalFormat(proxyInternalFormat);
        return levelParameter(pname, ok, proxyWidth[level], proxyHeight[level], proxyInternalFormat,
            cls == PixelFormatConverter.CLASS_UNSUPPORTED ? PixelFormatConverter.CLASS_RGBA : cls);
    }

    // =========================================================================================================
    // read back
    // =========================================================================================================

    /** glGetTexImage into {@code dst} ({@code dstBytes} writable bytes): synchronous, converts to the requested format and type. */
    public static void getTexImage(int target, int level, int format, int type, long dst, long dstBytes) {
        if (target == GL11.GL_PROXY_TEXTURE_2D) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        if (target != GL11.GL_TEXTURE_2D) {
            unsupportedTarget("glGetTexImage", target);
            return;
        }
        if (level < 0 || level >= MAX_LEVELS) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        if (PixelFormatConverter.isDepthOrStencil(format)) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        int bpp = checkTransfer("glGetTexImage", format, type);
        if (bpp <= 0) {
            return;
        }
        TextureObject tex = current2D();
        if (tex != null) {
            settleMips(tex); // the read goes to the main command buffer: a pending regeneration has to be recorded in front of it
        }
        if (tex == null || !tex.hasImage() || level >= tex.mipLevels || !tex.isDefined(level)) {
            return;
        }
        int w = levelDim(tex.width, level);
        int h = levelDim(tex.height, level);
        checkAvailable(PixelFormatConverter.requiredBytes(w, h, bpp, pack), dstBytes);
        long stride = PixelFormatConverter.rowStride(w, bpp, pack);
        long row0 = dst + PixelFormatConverter.startOffset(bpp, stride, pack);
        if (!ensureReadback((long) w * h * 4)) {
            return;
        }

        // texture -> host visible buffer on the main command buffer, then wait: uploads still pending on the upload command buffer
        // are executed first by flushAndWait
        RenderCore.ensureNoRenderPass();
        VkCommandBuffer cb = RenderCore.mainCommandBuffer();
        TextureCommands.barrier(cb, tex.image, level, 1, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        TextureCommands.copyImageToBuffer(cb, tex.image, level, w, h, readbackBuffer, 0L);
        TextureCommands.barrier(cb, tex.image, level, 1, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        TextureCommands.hostReadBarrier(cb, readbackBuffer);
        tex.lastUsedFrame = RenderCore.frameNumber();
        RenderCore.flushAndWait();
        Vma.vmaInvalidateAllocation(RenderCore.allocator(), readbackAllocation, 0L, VK10.VK_WHOLE_SIZE);
        boolean opaque = tex.copyOpaque || !PixelFormatConverter.hasAlpha(tex.formatClass);
        PixelFormatConverter.fromRgba8(readbackAddress, true, w, h, format, type, row0, stride, opaque, false);
    }

    /** glReadPixels of the (offscreen) default framebuffer into {@code dst} ({@code dstBytes} writable bytes). */
    public static void readPixels(int x, int y, int width, int height, int format, int type, long dst, long dstBytes) {
        if (width < 0 || height < 0) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        boolean depthStencil = PixelFormatConverter.isDepthOrStencil(format);
        int bpp;
        if (depthStencil) {
            bpp = PixelFormatConverter.depthStencilBytesPerPixel(format, type);
            if (bpp < 0) {
                GLErrors.set(GL11.GL_INVALID_ENUM);
                return;
            }
        } else {
            bpp = checkTransfer("glReadPixels", format, type);
            if (bpp <= 0) {
                return;
            }
        }
        if (width == 0 || height == 0) {
            return;
        }
        checkAvailable(PixelFormatConverter.requiredBytes(width, height, bpp, pack), dstBytes);
        long stride = PixelFormatConverter.rowStride(width, bpp, pack);
        long origin = dst + PixelFormatConverter.startOffset(bpp, stride, pack);
        if (depthStencil) {
            // the offscreen depth buffer is not readable through this path: report zeros instead of garbage
            GLUnsupported.call("glReadPixels(depth/stencil)");
            for (int row = 0; row < height; row++) {
                MemoryUtil.memSet(origin + row * stride, 0, (long) width * bpp);
            }
            return;
        }
        long fbW = RenderCore.framebufferWidth();
        long fbH = RenderCore.framebufferHeight();
        long x0 = Math.max((long) x, 0L);
        long y0 = Math.max((long) y, 0L);
        long x1 = Math.min((long) x + width, fbW);
        long y1 = Math.min((long) y + height, fbH);
        if (x0 >= x1 || y0 >= y1) {
            return; // GL leaves pixels outside the framebuffer untouched / undefined
        }
        int cw = (int) (x1 - x0);
        int ch = (int) (y1 - y0);
        long scratchBytes = (long) cw * ch * 4;
        if (scratchBytes > Integer.MAX_VALUE) {
            GLErrors.set(GL11.GL_OUT_OF_MEMORY); // cannot happen with the texture size limit, the framebuffer is never that large
            return;
        }
        ByteBuffer scratch = readScratch((int) scratchBytes);
        RenderCore.readColor((int) x0, (int) y0, cw, ch, scratch); // tightly packed RGBA8, rows bottom-up like GL
        long row0 = origin + (y0 - y) * stride + (x0 - x) * bpp;
        PixelFormatConverter.fromRgba8(MemoryUtil.memAddress(scratch), false, cw, ch, format, type, row0, stride, true, true);
        if (scratch.capacity() > READ_SCRATCH_KEEP) {
            freeReadScratch();
        }
    }

    private static ByteBuffer readScratch(int bytes) {
        if (readScratch == null || readScratch.capacity() < bytes) {
            freeReadScratch();
            readScratch = MemoryUtil.memAlloc(bytes);
        }
        readScratch.clear();
        readScratch.limit(bytes);
        return readScratch;
    }

    private static void freeReadScratch() {
        if (readScratch != null) {
            MemoryUtil.memFree(readScratch);
            readScratch = null;
        }
    }

    /** (Re)creates the host readback buffer so it holds at least {@code size} bytes; only called between flushAndWait pairs. */
    private static boolean ensureReadback(long size) {
        if (readbackBuffer != 0L && readbackSize >= size) {
            return true;
        }
        destroyReadback();
        long newSize = Math.max(size, 1L << 16);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(newSize)
                .usage(VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack)
                .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            VmaAllocationInfo allocInfo = VmaAllocationInfo.calloc(stack);
            int result = Vma.vmaCreateBuffer(RenderCore.allocator(), info, alloc, pBuffer, pAllocation, allocInfo);
            if (result != VK10.VK_SUCCESS || allocInfo.pMappedData() == 0L) {
                VkLog.LOGGER.error("Creating the texture readback buffer ({} bytes) failed: VkResult {}", newSize, result);
                if (result == VK10.VK_SUCCESS) {
                    Vma.vmaDestroyBuffer(RenderCore.allocator(), pBuffer.get(0), pAllocation.get(0));
                }
                GLErrors.set(GL11.GL_OUT_OF_MEMORY);
                return false;
            }
            readbackBuffer = pBuffer.get(0);
            readbackAllocation = pAllocation.get(0);
            readbackAddress = allocInfo.pMappedData();
            readbackSize = newSize;
            return true;
        }
    }

    private static void destroyReadback() {
        if (readbackBuffer != 0L) {
            // every use is followed by RenderCore.flushAndWait, so nothing in flight references the buffer
            Vma.vmaDestroyBuffer(RenderCore.allocator(), readbackBuffer, readbackAllocation);
        }
        readbackBuffer = 0L;
        readbackAllocation = 0L;
        readbackAddress = 0L;
        readbackSize = 0L;
    }

    // =========================================================================================================
    // Vulkan objects: image creation, views, descriptor sets, deferred destruction
    // =========================================================================================================

    /** Creates a VkImage (+ VMA allocation) for the texture. On failure the texture keeps no image and GL_OUT_OF_MEMORY is raised. */
    private static boolean allocateImage(TextureObject tex, int width, int height, int levels) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK10.VK_IMAGE_TYPE_2D)
                .format(VK10.VK_FORMAT_B8G8R8A8_UNORM)
                .mipLevels(levels)
                .arrayLayers(1)
                .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                .usage(VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO);
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            int result = Vma.vmaCreateImage(RenderCore.allocator(), info, alloc, pImage, pAllocation, null);
            if (result != VK10.VK_SUCCESS) {
                VkLog.LOGGER.error("vmaCreateImage {}x{} ({} levels) failed: VkResult {}", width, height, levels, result);
                GLErrors.set(GL11.GL_OUT_OF_MEMORY);
                return false;
            }
            tex.image = pImage.get(0);
            tex.allocation = pAllocation.get(0);
            tex.width = width;
            tex.height = height;
            tex.mipLevels = levels;
            tex.imageView = 0L;
            tex.viewKey = -1;
            tex.lastUsedFrame = -1L;
            tex.setDirty = true;
            return true;
        }
    }

    /** Detaches the Vulkan objects from the texture and schedules their destruction after the frames in flight. */
    private static void releaseImage(TextureObject tex) {
        final long view = tex.imageView;
        final long image = tex.image;
        final long allocation = tex.allocation;
        tex.freeAllSets();
        tex.image = 0L;
        tex.allocation = 0L;
        tex.imageView = 0L;
        tex.viewKey = -1;
        tex.width = 0;
        tex.height = 0;
        tex.mipLevels = 0;
        tex.definedMask = 0;
        tex.copyOpaque = false;
        tex.mipsStale = false;
        tex.lastUsedFrame = -1L;
        tex.errorLogged = false;
        if (view != 0L || image != 0L) {
            RenderCore.deferDestroy(() -> destroyImageObjects(view, image, allocation));
        }
    }

    private static void destroyImageObjects(long view, long image, long allocation) {
        if (view != 0L) {
            VK10.vkDestroyImageView(RenderCore.device(), view, null);
        }
        if (image != 0L) {
            Vma.vmaDestroyImage(RenderCore.allocator(), image, allocation);
        }
    }

    /** Synchronous destruction at shutdown (device idle, descriptor pools are destroyed afterwards). */
    private static void destroyNow(TextureObject tex) {
        destroyImageObjects(tex.imageView, tex.image, tex.allocation);
        tex.image = 0L;
        tex.allocation = 0L;
        tex.imageView = 0L;
        tex.viewKey = -1;
        tex.mipLevels = 0;
        tex.dropAllSets();
    }

    /**
     * Replaces the image by one with {@code newLevels} levels, copying the existing levels (vkCmdCopyImage) on the command buffer
     * chosen by the texture's frame usage (always the upload one with {@code uploadOnly}, see {@link #generateLevels}). The old image is
     * destroyed after the frames in flight; cached descriptor sets are rebuilt on the next use.
     */
    private static boolean growMipChain(TextureObject tex, int newLevels, boolean uploadOnly) {
        newLevels = Math.min(newLevels, fullLevels(tex.width, tex.height));
        if (newLevels <= tex.mipLevels) {
            return true;
        }
        final long oldImage = tex.image;
        final long oldAllocation = tex.allocation;
        final long oldView = tex.imageView;
        int oldLevels = tex.mipLevels;
        int w = tex.width;
        int h = tex.height;
        long oldLastUsed = tex.lastUsedFrame;
        // on failure allocateImage leaves the texture (and its old image) untouched; on success the new image replaces it
        if (!allocateImage(tex, w, h, newLevels)) {
            return false;
        }
        tex.lastUsedFrame = oldLastUsed; // the contents continue the old image's history in this frame
        VkCommandBuffer cb = commandBufferFor(tex, uploadOnly);
        long newImage = tex.image;
        TextureCommands.barrier(cb, newImage, 0, newLevels, VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        TextureCommands.barrier(cb, oldImage, 0, oldLevels, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        TextureCommands.copyImageLevels(cb, oldImage, newImage, oldLevels, w, h);
        TextureCommands.clearColor(cb, newImage, oldLevels, newLevels - oldLevels, 0f, 0f, 0f,
            PixelFormatConverter.hasAlpha(tex.formatClass) ? 0f : 1f);
        TextureCommands.barrier(cb, newImage, 0, newLevels, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        // the old image rests in TRANSFER_SRC from here on and is never sampled again
        tex.freeAllSets();
        RenderCore.deferDestroy(() -> destroyImageObjects(oldView, oldImage, oldAllocation));
        return true;
    }

    /** Number of levels a freshly specified level 0 is created with (see {@link #growTarget} for later growth). */
    private static int initialLevels(TextureObject.Params p, int w, int h) {
        int full = fullLevels(w, h);
        if (p.maxLevelSet) {
            return Math.max(1, Math.min(full, p.maxLevel + 1)); // MITE style: GL_TEXTURE_MAX_LEVEL first, then every level
        }
        return p.generateMipmap ? full : 1;
    }

    /** Levels to grow to when level {@code level} (not allocated yet) is specified: normally the whole chain, an application that
     * specifies one mip level nearly always specifies the rest too. */
    private static int growTarget(TextureObject tex, int level) {
        int full = fullLevels(tex.width, tex.height);
        return tex.params.maxLevelSet ? Math.min(full, Math.max(level + 1, tex.params.maxLevel + 1)) : full;
    }

    /** Builds / replaces the image view, picks the sampler and makes sure a descriptor set for (view, sampler) exists. */
    private static void resolveDescriptor(TextureObject tex) {
        TextureObject.Params p = tex.params;
        int base = Math.min(Math.max(p.baseLevel, 0), tex.mipLevels - 1);
        int limit = Math.max(base, Math.min(p.maxLevel, tex.mipLevels - 1));
        int last = base;
        while (last < limit && tex.isDefined(last + 1)) {
            last++; // only levels that were really specified are sampled
        }
        int levels = last - base + 1;
        boolean opaque = tex.copyOpaque && TextureSamplers.viewSwizzleSupported();
        int viewKey = base | levels << 8 | (opaque ? 1 << 16 : 0);
        if (tex.imageView == 0L || tex.viewKey != viewKey) {
            final long oldView = tex.imageView;
            tex.freeAllSets();
            tex.imageView = 0L;
            tex.viewKey = -1;
            if (oldView != 0L) {
                RenderCore.deferDestroy(() -> VK10.vkDestroyImageView(RenderCore.device(), oldView, null));
            }
            long view = createView(tex.image, base, levels, opaque);
            if (view == 0L) {
                failDescriptor(tex, "Creating an image view for texture " + tex.name + " failed");
                return;
            }
            tex.imageView = view;
            tex.viewKey = viewKey;
        }
        long sampler = TextureSamplers.get(p, levels);
        long set = tex.findSet(sampler);
        if (set == 0L) {
            TextureDescriptors.Allocation a = TextureDescriptors.allocate();
            if (a == null) {
                failDescriptor(tex, "Allocating a descriptor set for texture " + tex.name + " failed");
                return;
            }
            TextureDescriptors.write(a.set(), sampler, tex.imageView);
            tex.addSet(sampler, a.set(), a.pool());
            set = a.set();
        }
        tex.currentSet = set;
        tex.setDirty = false;
    }

    /** The texture cannot be sampled: it falls back to the white texture until something changes (logged once per image). */
    private static void failDescriptor(TextureObject tex, String message) {
        if (!tex.errorLogged) {
            tex.errorLogged = true;
            VkLog.LOGGER.error("{}; the texture is replaced by the white texture", message);
        }
        tex.currentSet = 0L;
        tex.setDirty = false;
    }

    private static long createView(long image, int base, int levels, boolean opaque) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                .format(VK10.VK_FORMAT_B8G8R8A8_UNORM);
            if (opaque) {
                info.components().a(VK10.VK_COMPONENT_SWIZZLE_ONE); // r, g, b stay IDENTITY (0)
            }
            info.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(base)
                .levelCount(levels)
                .baseArrayLayer(0)
                .layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            int result = VK10.vkCreateImageView(RenderCore.device(), info, null, pView);
            if (result != VK10.VK_SUCCESS) {
                VkLog.LOGGER.error("vkCreateImageView failed: VkResult {}", result);
                return 0L;
            }
            return pView.get(0);
        }
    }

    // =========================================================================================================
    // helpers
    // =========================================================================================================

    /**
     * The command buffer a write to the texture is recorded on: the main one (outside a render pass) when the main command buffer
     * already references the texture in this frame, so the write is ordered after those uses; otherwise the upload command buffer.
     */
    private static VkCommandBuffer commandBufferFor(TextureObject tex) {
        if (tex.lastUsedFrame == RenderCore.frameNumber()) {
            RenderCore.ensureNoRenderPass();
            return RenderCore.mainCommandBuffer();
        }
        return RenderCore.uploadCommandBuffer();
    }

    private static VkCommandBuffer commandBufferFor(TextureObject tex, boolean uploadOnly) {
        return uploadOnly ? RenderCore.uploadCommandBuffer() : commandBufferFor(tex);
    }

    /** Reserves {@code bytes} (4 byte aligned) of staging memory; the result is left in the stagingBuffer / Offset / Address fields. */
    private static void stagingAlloc(long bytes) {
        StreamSlice slice = RenderCore.streamStaging((int) bytes + 3);
        long shift = (4L - (slice.offset() & 3L)) & 3L; // vkCmdCopyBufferToImage needs a multiple of the texel size (4)
        stagingBuffer = slice.buffer();
        stagingOffset = slice.offset() + shift;
        stagingAddress = slice.address() + shift;
    }

    /** The GL_TEXTURE_2D object bound on the active texture unit (null for an out of range unit). */
    private static TextureObject current2D() {
        int unit = GLState.get().activeTexture();
        if (unit < 0 || unit >= MAX_UNITS) {
            return null;
        }
        return lookup(bound[unit]);
    }

    private static int maxTextureSize() {
        return Math.min(MAX_TEXTURE_SIZE, RenderCore.deviceInfo().maxImageDimension2D());
    }

    /** Validates a pixel transfer (format, type): bytes per pixel, or 0 after reporting the problem. */
    private static int checkTransfer(String function, int format, int type) {
        int bpp = PixelFormatConverter.bytesPerPixel(format, type);
        if (bpp > 0) {
            return bpp;
        }
        if (bpp < 0) {
            boolean combination = PixelFormatConverter.components(format) != 0 && PixelFormatConverter.isPacked8888(type);
            GLErrors.set(combination ? GL11.GL_INVALID_OPERATION : GL11.GL_INVALID_ENUM);
        } else {
            GLUnsupported.call(function + "(format 0x" + Integer.toHexString(format) + ", type 0x" + Integer.toHexString(type) + ")");
        }
        return 0;
    }

    /** Memory safety: LWJGL 2 rejects a buffer that is too small with an IllegalArgumentException, so do we. */
    private static void checkAvailable(long required, long available) {
        if (required > available) {
            throw new IllegalArgumentException("Number of remaining buffer bytes is " + available + ", must be at least " + required);
        }
    }

    private static void unsupportedTarget(String function, int target) {
        GLUnsupported.call(function + "(target 0x" + Integer.toHexString(target) + ")");
    }

    private static void warnOnce(String key, String message) {
        if (WARNED.add(key)) {
            VkLog.LOGGER.warn(message);
        }
    }

    /** floor(log2(max(w, h))) + 1: the number of levels of a complete mip chain. */
    static int fullLevels(int w, int h) {
        int m = Math.max(w, h);
        return m <= 0 ? 1 : 32 - Integer.numberOfLeadingZeros(m);
    }

    static int levelDim(int base, int level) {
        return Math.max(1, base >> level);
    }
}
