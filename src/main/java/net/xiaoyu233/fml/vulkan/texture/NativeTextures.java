package net.xiaoyu233.fml.vulkan.texture;

import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.gl.GLErrors;
import net.xiaoyu233.fml.vulkan.render.buffer.Batches;
import org.lwjgl.opengl.GL11;

/**
 * Implementation of the native texture facade {@code render.texture.Textures} (1.11, 1.12, 5.7) over the phase-1 store: ids are the GL
 * names of {@link TextureManager}, so the safety net, {@code AbstractTexture.glTextureId} and native batches agree. Everything here
 * works on the texture object without touching bindings or the GL state machine; the render thread is the only caller.
 */
public final class NativeTextures {
    private NativeTextures() {
    }

    /**
     * ARGB upload of a {@code w x h} block (tightly packed) into level 0 at (x, y): the ints are copied to staging as they are
     * ({@code B,G,R,A} bytes), recorded on the upload command buffer (coalesced inside an upload batch). An atlas with mip levels
     * rebuilds the levels of the rectangle. NEAREST / LINEAR and REPEAT / CLAMP follow the flags.
     */
    public static void uploadArgb(int id, int[] argb, int w, int h, int x, int y, boolean linear, boolean clamp) {
        TextureObject tex = TextureManager.find(id);
        if (tex == null || !tex.hasImage()) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        if (w <= 0 || h <= 0) {
            return;
        }
        if (x < 0 || y < 0 || (long) x + w > tex.width || (long) y + h > tex.height) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        if (argb.length < w * h) {
            throw new IllegalArgumentException("Number of remaining pixels is " + argb.length + ", must be at least " + w * h);
        }
        applySampler(tex, linear, clamp);
        UploadEngine.addRegion(tex, 0, x, y, w, h, argb, 0);
        if (tex.atlasLevels > 1) {
            uploadMipLevels(tex, argb, w, h, x, y);
        }
        AtlasData atlas = tex.atlas;
        if (atlas != null) {
            atlas.onUpload(x, y, w, h, argb);
        }
    }

    /** Levels 1.. of a sprite rectangle: alpha-weighted box filter, each sprite reduced on its own (cutout safe). */
    private static void uploadMipLevels(TextureObject tex, int[] argb, int w, int h, int x, int y) {
        int deepest = Math.min(tex.atlasLevels - 1, Integer.numberOfTrailingZeros(x | y | w | h));
        int[] source = argb;
        int sourceW = w;
        int sourceH = h;
        for (int level = 1; level <= deepest; level++) {
            int levelW = sourceW >> 1;
            int levelH = sourceH >> 1;
            if (levelW == 0 || levelH == 0) {
                break;
            }
            int[] reduced = AtlasMips.buffer(level, levelW * levelH);
            AtlasMips.downsample(source, 0, sourceW, sourceH, reduced);
            UploadEngine.addRegion(tex, level, x >> level, y >> level, levelW, levelH, reduced, 0);
            source = reduced;
            sourceW = levelW;
            sourceH = levelH;
        }
    }

    /** Applies the sampler flags; a change re-resolves the descriptor, so batched draws of the old sampler are closed first. */
    private static void applySampler(TextureObject tex, boolean linear, boolean clamp) {
        boolean filter = TextureManager.filterChanges(tex, linear);
        boolean wrap = TextureManager.wrapChanges(tex, clamp);
        if (filter || wrap) {
            Batches.flushIfTextureReferenced(tex.name);
            TextureManager.applyFilter(tex, linear);
            TextureManager.applyWrap(tex, clamp);
        }
    }

    public static void setFilter(int id, boolean linear) {
        TextureObject tex = TextureManager.obtain(id);
        if (tex != null && TextureManager.filterChanges(tex, linear)) {
            Batches.flushIfTextureReferenced(id);
            TextureManager.applyFilter(tex, linear);
        }
    }

    public static void setWrap(int id, boolean clamp) {
        TextureObject tex = TextureManager.obtain(id);
        if (tex != null && TextureManager.wrapChanges(tex, clamp)) {
            Batches.flushIfTextureReferenced(id);
            TextureManager.applyWrap(tex, clamp);
        }
    }

    /** (Re)creates {@code id} as an empty width x height texture (all zero) without mip levels. */
    public static void allocate(int id, int width, int height) {
        Batches.flushIfTextureReferenced(id);
        TextureObject tex = TextureManager.find(id);
        if (tex != null && tex.hasImage() && tex.mipLevels > 1) {
            TextureManager.releaseImage(tex); // a plain texture never keeps the levels of an atlas it was before
        }
        if (tex != null) {
            tex.atlasLevels = 1;
            tex.atlas = null;
        }
        TextureManager.allocateNative(id, width, height, 1, 1f);
    }

    /**
     * (Re)creates an atlas. With {@code fml.vulkan.mipmaps=N} the image gets up to N extra levels, limited by the size and by
     * {@code alignmentBits} (the number of times every sprite rectangle can be halved: levels deeper than that would mix sprites and
     * leave holes), sampled with {@code fml.vulkan.anisotropy}; the opacity bookkeeping starts empty.
     */
    public static void allocateAtlas(int id, int width, int height, int alignmentBits) {
        Batches.flushIfTextureReferenced(id);
        TextureObject tex = TextureManager.find(id);
        if (tex != null && tex.hasImage()) {
            TextureManager.releaseImage(tex); // fresh image: every level starts cleared
        }
        int extra = Math.max(0, Math.min(Math.min(VulkanConfig.mipmaps(), 4), alignmentBits));
        TextureManager.allocateNative(id, width, height, extra + 1, VulkanConfig.anisotropy());
        tex = TextureManager.find(id);
        if (tex != null) {
            tex.atlas = null;
        }
        AtlasData.begin(id, width, height);
    }

    /** Opacity of one stitched sprite rectangle of the atlas being built. */
    public static void atlasSprite(int id, int x, int y, int w, int h, boolean opaque) {
        AtlasData.sprite(id, x, y, w, h, opaque);
    }

    /** Publishes the opacity map of the atlas built since {@link #allocateAtlas}. */
    public static void registerAtlas(int id) {
        AtlasData.register(id);
        TextureObject tex = TextureManager.find(id);
        if (tex != null) {
            tex.atlas = AtlasData.get(id);
        }
    }

    public static AtlasData atlas(int id) {
        return AtlasData.get(id);
    }

    public static void beginUploadBatch() {
        UploadEngine.beginBatch();
    }

    public static void endUploadBatch() {
        UploadEngine.endBatch();
    }

    public static long descriptorSet(int id) {
        return TextureManager.descriptorSetFor(id);
    }

    public static int width(int id) {
        return TextureManager.widthOf(id);
    }

    public static int height(int id) {
        return TextureManager.heightOf(id);
    }

    public static int levels(int id) {
        TextureObject tex = TextureManager.find(id);
        return tex == null ? 0 : tex.mipLevels;
    }

    /**
     * Copies a rectangle of a target's colour image (GL coordinates) into level 0 of {@code dstId}; the one sanctioned inline copy
     * (ends the render pass, counted). {@code srcImage == 0} means the MAIN target of the phase-1 core. A non-main source image must
     * rest in COLOR_ATTACHMENT_OPTIMAL (the finalLayout of the offscreen render passes of {@code core.OffscreenTarget}, which is also the
     * layout the copy returns it to); a target that was transitioned to a sampled layout has to be moved back by its owner first.
     */
    public static void copyFromTarget(long srcImage, int srcWidth, int srcHeight, int srcX, int srcY, int dstId, int dstX, int dstY, int w,
                                      int h) {
        TextureObject tex = TextureManager.find(dstId);
        if (tex == null || !tex.hasImage()) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        if (w <= 0 || h <= 0) {
            return;
        }
        if (dstX < 0 || dstY < 0 || (long) dstX + w > tex.width || (long) dstY + h > tex.height) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        // the source must contain everything drawn so far: record every pending batch before the copy ends the render pass
        net.xiaoyu233.fml.vulkan.render.buffer.Batches.flushPhase();
        Batches.flushIfTextureReferenced(dstId);
        UploadEngine.flushTexture(tex);
        boolean copied = srcImage == 0L ? TextureManager.copyFromFramebuffer(tex, 0, dstX, dstY, srcX, srcY, w, h)
            : TextureManager.copyFromTargetImage(tex, srcImage, srcWidth, srcHeight, dstX, dstY, srcX, srcY, w, h);
        if (copied && dstX == 0 && dstY == 0 && w == tex.width && h == tex.height) {
            TextureManager.setCopyOpaque(tex); // the targets never write alpha: it reads 1
        }
        if (copied) {
            TextureManager.afterLevel0Write(tex);
        }
    }

    /** Copies recorded for the frame being recorded (render passes ended for a texture write). */
    public static int inlineCopiesThisFrame() {
        return TextureFrameHooks.currentFrame();
    }

    /** Copies recorded in the previous (completed) frame; what a reader that runs after present should use. */
    public static int inlineCopiesLastFrame() {
        return TextureFrameHooks.previousFrame();
    }

    /** Backing images beyond the current one of {@code id} (versioned textures), for tests and diagnostics. */
    public static int spareImages(int id) {
        TextureObject tex = TextureManager.find(id);
        return tex == null ? 0 : VersionPool.spareImages(tex);
    }

    /** Current frame number of the core (tests). */
    public static long frameNumber() {
        return RenderCore.frameNumber();
    }
}
