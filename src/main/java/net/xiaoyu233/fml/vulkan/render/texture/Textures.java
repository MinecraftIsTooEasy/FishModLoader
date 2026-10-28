package net.xiaoyu233.fml.vulkan.render.texture;

import net.xiaoyu233.fml.vulkan.core.DeviceInfo;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.gl.state.GLState;
import net.xiaoyu233.fml.vulkan.render.gpu.RenderTarget;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.texture.NativeTextures;
import net.xiaoyu233.fml.vulkan.texture.TextureManager;

/**
 * Texture facade of the native renderer (1.11, 1.12, 5.7): the API of game code and native systems over the phase-1
 * {@link TextureManager} store; <b>texture ids are the GL names</b> of that store. Uploads take ARGB ints as they are (little-endian
 * {@code B,G,R,A} = {@code B8G8R8A8_UNORM}, no per-pixel conversion) and are recorded on the upload command buffer, which runs before
 * the frame's render pass. A texture that is re-uploaded after the frame sampled it switches to another backing image (versioned
 * textures) and re-points its descriptor, so no render pass is ever broken for an upload; only {@link #copyFromTarget} (and the
 * versioning-off fallback of {@code fml.vulkan.textureVersions=false}) records inline, and every inline copy is counted.
 * Render thread only.
 */
public final class Textures {
    private static final int MAX_SIZE = 16384;

    /** Set once {@code Draw} reported that it is not implemented yet (the NR-00 stub throws); the GL emulation's unit is used then. */
    private static boolean drawUnavailable;
    /** While {@code Draw.bindTexture} runs (it may call {@link #bind} back) this is the unit the outer call resolved. */
    private static int reentryUnit = -1;

    private Textures() {
    }

    /** Active texture unit: the native draw state's, the GL emulation's before the draw state exists. */
    private static int unit() {
        if (reentryUnit >= 0) {
            return reentryUnit;
        }
        if (!drawUnavailable) {
            try {
                return Draw.activeUnit();
            } catch (UnsupportedOperationException notYet) {
                drawUnavailable = true;
            }
        }
        return GLState.get().activeTexture();
    }

    public static int create() {
        return TextureManager.genTextures();
    }

    public static void delete(int id) {
        TextureManager.deleteTextures(id);
    }

    /** Binds on the active unit (= {@code TextureUtil.bindTexture}); the native draw state mirrors the binding. */
    public static void bind(int id) {
        if (reentryUnit >= 0) {
            TextureManager.bindTexture(reentryUnit, id);
            return;
        }
        int unit = unit();
        TextureManager.bindTexture(unit, id);
        if (!drawUnavailable) {
            reentryUnit = unit;
            try {
                Draw.bindTexture(id);
            } catch (UnsupportedOperationException notYet) {
                drawUnavailable = true;
            } finally {
                reentryUnit = -1;
            }
        }
    }

    public static int bound() {
        return TextureManager.boundTexture(unit());
    }

    /** (Re)creates {@code id} as an empty (all zero) {@code width x height} texture; bindings are not touched. */
    public static void allocate(int id, int width, int height) {
        NativeTextures.allocate(id, width, height);
    }

    /**
     * (Re)creates an atlas texture; {@link #atlasSprite} and {@link #registerAtlas} follow. Mip levels come from
     * {@code fml.vulkan.mipmaps}, limited to {@code alignmentBits} (how often every sprite rectangle halves exactly, see
     * {@link #alignmentBits}).
     */
    public static void allocateAtlas(int id, int width, int height, int alignmentBits) {
        NativeTextures.allocateAtlas(id, width, height, alignmentBits);
    }

    /** How many times the rectangle (x, y, w, h) halves exactly: the deepest mip level of an atlas that keeps it free of other sprites. */
    public static int alignmentBits(int x, int y, int w, int h) {
        return Integer.numberOfTrailingZeros(x | y | w | h | 1 << 8);
    }

    /** Declares one stitched sprite rectangle of the atlas being built and whether all of its alpha values are 255. */
    public static void atlasSprite(int id, int x, int y, int w, int h, boolean opaque) {
        NativeTextures.atlasSprite(id, x, y, w, h, opaque);
    }

    /** Uploads {@code w x h} ARGB ints (row 0 first) into level 0 at (x, y); nearest / linear and repeat / clamp from the flags. */
    public static void uploadArgb(int id, int[] argb, int width, int height, int x, int y, boolean linear, boolean clamp) {
        NativeTextures.uploadArgb(id, argb, width, height, x, y, linear, clamp);
    }

    public static void setFilter(int id, boolean linear) {
        NativeTextures.setFilter(id, linear);
    }

    public static void setWrap(int id, boolean clamp) {
        NativeTextures.setWrap(id, clamp);
    }

    /** {@code min(maxImageDimension2D, 16384)}: MITE's size probe answer. */
    public static int maxSize() {
        DeviceInfo info = RenderCore.deviceInfo();
        return info == null ? MAX_SIZE : Math.min(MAX_SIZE, info.maxImageDimension2D());
    }

    /**
     * Copies a rectangle of {@code src}'s colour image (GL coordinates: origin bottom left, clipped to the target) into level 0 of
     * {@code dstId}; replaces {@code glCopyTexSubImage2D} (panorama). Ends the render pass: the one sanctioned inline copy.
     */
    public static void copyFromTarget(RenderTarget src, int srcX, int srcY, int dstId, int dstX, int dstY, int w, int h) {
        if (src == null || src == RenderTarget.main()) {
            NativeTextures.copyFromTarget(0L, 0, 0, srcX, srcY, dstId, dstX, dstY, w, h);
        } else {
            NativeTextures.copyFromTarget(src.colorImage(), src.width(), src.height(), srcX, srcY, dstId, dstX, dstY, w, h);
        }
    }

    /** Called by {@code TextureMap} after stitching: publishes the opacity map of the atlas {@code id}. */
    public static void registerAtlas(int id) {
        NativeTextures.registerAtlas(id);
    }

    /** The registered atlas with this id, or null when it is not an atlas. */
    public static AtlasInfo atlas(int id) {
        return NativeTextures.atlas(id);
    }

    /** Uploads between begin and end share one staging block and one copy command per texture (TEX-03); nests. */
    public static void beginUploadBatch() {
        NativeTextures.beginUploadBatch();
    }

    public static void endUploadBatch() {
        NativeTextures.endUploadBatch();
    }

    /** Descriptor set of the current version of {@code id} (the white texture's when it has no image); marks it used this frame. */
    public static long descriptorSet(int id) {
        return NativeTextures.descriptorSet(id);
    }

    /** Inline copies recorded in the frame being recorded; {@link #inlineCopiesLastFrame} is the completed frame. */
    public static int inlineCopiesThisFrame() {
        return NativeTextures.inlineCopiesThisFrame();
    }

    public static int inlineCopiesLastFrame() {
        return NativeTextures.inlineCopiesLastFrame();
    }

    public static int width(int id) {
        return NativeTextures.width(id);
    }

    public static int height(int id) {
        return NativeTextures.height(id);
    }
}
