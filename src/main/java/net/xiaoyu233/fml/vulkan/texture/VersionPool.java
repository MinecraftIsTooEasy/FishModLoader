package net.xiaoyu233.fml.vulkan.texture;

import java.util.ArrayList;
import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Versioned dynamic textures (1.11, 1.12). A texture that is written after the main command buffer already sampled it in the current
 * frame (held map, lightmap, skins, animated atlas ticked mid-frame) does not break the render pass: it switches to another backing
 * image of the same size, the copy goes to the upload command buffer (which runs before the frame's render pass) and the descriptor
 * set is re-pointed, so draws recorded after the call sample the new image while the ones recorded before keep the old one.
 *
 * <p>The images are created lazily: the first mid-frame write of a texture adds one, a texture written N times in one frame ends up
 * with N + 1 images, and every later frame reuses them (a frame in flight may still sample an image the new frame overwrites: the
 * layout transition of the upload command buffer waits for the earlier shader reads, like every other upload). At most
 * {@link #MAX_IMAGES} images exist per texture; past that, or with {@code fml.vulkan.textureVersions=false}, the write is recorded
 * inline on the main command buffer (ends the render pass, counted in {@code inlineCopiesThisFrame}). Render thread only.
 */
final class VersionPool {
    /** Backing images per texture (current + spare). */
    static final int MAX_IMAGES = 8;
    /** Backing images of a texture with mip levels (atlases): the switch copies every level, so fewer images are kept (inline copy beyond). */
    static final int MAX_MIPPED_IMAGES = 3;

    private static final ArrayList<TextureObject> VERSIONED = new ArrayList<>();

    private VersionPool() {
    }

    static void reset() {
        VERSIONED.clear();
    }

    /** Number of textures that currently own spare images (diagnostics). */
    static int versionedTextures() {
        return VERSIONED.size();
    }

    /** Spare images of {@code tex} (diagnostics). */
    static int spareImages(TextureObject tex) {
        return tex.spare == null ? 0 : tex.spare.size();
    }

    /**
     * The command buffer a write into the current image of {@code tex} has to be recorded on, switching to another backing image when
     * the current one was already sampled in this frame.
     *
     * @param fullOverwrite the write replaces every texel (a switch then needs no copy of the old contents)
     */
    static VkCommandBuffer commandBufferForWrite(TextureObject tex, boolean fullOverwrite) {
        long frame = RenderCore.frameNumber();
        if (tex.lastUsedFrame != frame) {
            return RenderCore.uploadCommandBuffer();
        }
        // an inline write of this frame (copy from a target) lives on the main command buffer: a version copy on the upload one would miss it
        if (tex.mainWriteFrame != frame && VulkanConfig.textureVersions() && switchImage(tex, !fullOverwrite, frame)) {
            return RenderCore.uploadCommandBuffer();
        }
        return TextureManager.commandBufferFor(tex); // inline: ends the pass, counted
    }

    private static boolean switchImage(TextureObject tex, boolean keepContents, long frame) {
        ArrayList<TextureObject.Version> spares = tex.spare;
        TextureObject.Version version = null;
        if (spares != null) {
            for (int i = 0; i < spares.size(); i++) {
                TextureObject.Version candidate = spares.get(i);
                if (candidate.lastUsedFrame < frame) {
                    version = candidate;
                    break;
                }
            }
        }
        boolean fresh = false;
        if (version == null) {
            // a mipped texture (an atlas) copies every level on a switch and keeps every backing image at full chain size: smaller cap
            int cap = tex.mipLevels > 1 ? MAX_MIPPED_IMAGES : MAX_IMAGES;
            if ((spares == null ? 0 : spares.size()) + 1 >= cap) {
                return false;
            }
            if (tex.mipLevels > 1) {
                TextureManager.warnOnce("versionMipped" + tex.name, "Texture " + tex.name + " (" + tex.width + "x" + tex.height + ", "
                    + tex.mipLevels + " levels) was written after being sampled in the same frame: switching backing image (full level copy);"
                    + " worst case " + MAX_MIPPED_IMAGES + " images");
            }
            long[] handles = new long[2];
            if (!TextureManager.createImage(tex.width, tex.height, tex.mipLevels, handles)) {
                return false;
            }
            version = new TextureObject.Version();
            version.image = handles[0];
            version.allocation = handles[1];
            fresh = true;
            if (spares == null) {
                spares = new ArrayList<>(2);
                tex.spare = spares;
                VERSIONED.add(tex);
            }
            spares.add(version);
        }
        long oldImage = tex.image;
        int levels = tex.mipLevels;
        tex.swapState(version); // the texture now owns the new image, the version the one the earlier draws of this frame sampled
        VkCommandBuffer cb = RenderCore.uploadCommandBuffer();
        long newImage = tex.image;
        if (fresh) {
            TextureCommands.barrier(cb, newImage, 0, levels, VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        } else {
            TextureCommands.barrier(cb, newImage, 0, levels, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        }
        if (keepContents) {
            TextureCommands.barrier(cb, oldImage, 0, levels, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            TextureCommands.copyImageLevels(cb, oldImage, newImage, levels, tex.width, tex.height);
            TextureCommands.barrier(cb, oldImage, 0, levels, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        } else if (fresh) {
            TextureCommands.clearColor(cb, newImage, 0, levels, 0f, 0f, 0f, PixelFormatConverter.hasAlpha(tex.formatClass) ? 0f : 1f);
        }
        TextureCommands.barrier(cb, newImage, 0, levels, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        if (VulkanConfig.debug()) {
            VkLog.LOGGER.debug("Texture {} switched to backing image {} (spares {})", tex.name, Long.toHexString(newImage), spares.size());
        }
        return true;
    }

    /** The texture's image goes away: the spare images follow (after the frames in flight). */
    static void releaseSpares(TextureObject tex) {
        ArrayList<TextureObject.Version> spares = tex.spare;
        if (spares == null) {
            return;
        }
        tex.spare = null;
        VERSIONED.remove(tex);
        for (int i = 0; i < spares.size(); i++) {
            TextureObject.Version v = spares.get(i);
            for (int s = 0; s < v.setCount; s++) {
                TextureDescriptors.deferFree(v.sets[s], v.setPools[s]);
            }
            final long view = v.imageView;
            final long image = v.image;
            final long allocation = v.allocation;
            RenderCore.deferDestroy(() -> TextureManager.destroyImageObjects(view, image, allocation));
        }
    }

    /** Shutdown: the device is idle, the descriptor pools are destroyed afterwards. */
    static void destroySparesNow(TextureObject tex) {
        ArrayList<TextureObject.Version> spares = tex.spare;
        if (spares == null) {
            return;
        }
        tex.spare = null;
        for (int i = 0; i < spares.size(); i++) {
            TextureObject.Version v = spares.get(i);
            TextureManager.destroyImageObjects(v.imageView, v.image, v.allocation);
        }
    }
}
