package net.xiaoyu233.fml.vulkan.texture;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;

/**
 * Command recording helpers for texture images (module B). Every helper records on the command buffer it is given: the caller
 * decides between the main command buffer (after {@code RenderCore.ensureNoRenderPass()}) and the upload command buffer.
 *
 * <p>Invariant: between operations every level of a texture image rests in SHADER_READ_ONLY_OPTIMAL. An operation transitions the
 * subresources it touches and transitions them back, with the stage / access masks of the transition table in
 * {@link #barrier}.
 */
final class TextureCommands {
    /** The stages that sample textures; the same mask is used wherever shaders are the other side of a barrier (keeps chains intact). */
    private static final int SHADER_STAGES = VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

    private TextureCommands() {
    }

    /**
     * Image layout transition of {@code levelCount} levels starting at {@code baseMip}. Supported layouts: UNDEFINED (old only),
     * SHADER_READ_ONLY_OPTIMAL, TRANSFER_DST_OPTIMAL, TRANSFER_SRC_OPTIMAL.
     *
     * <pre>
     *   old                    src stage / access                     new                    dst stage / access
     *   UNDEFINED              TOP_OF_PIPE / 0                        TRANSFER_DST           TRANSFER / TRANSFER_WRITE
     *   SHADER_READ_ONLY       VERTEX|FRAGMENT / SHADER_READ          TRANSFER_SRC           TRANSFER / TRANSFER_READ
     *   TRANSFER_DST           TRANSFER / TRANSFER_WRITE              SHADER_READ_ONLY       VERTEX|FRAGMENT / SHADER_READ
     *   TRANSFER_SRC           TRANSFER / 0
     * </pre>
     */
    static void barrier(VkCommandBuffer cb, long image, int baseMip, int levelCount, int oldLayout, int newLayout) {
        int srcStage;
        int srcAccess;
        switch (oldLayout) {
            case VK10.VK_IMAGE_LAYOUT_UNDEFINED -> {
                srcStage = VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                srcAccess = 0;
            }
            case VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                srcStage = SHADER_STAGES;
                srcAccess = VK10.VK_ACCESS_SHADER_READ_BIT;
            }
            case VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                srcStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
                srcAccess = VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
            }
            case VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> {
                srcStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
                srcAccess = 0;
            }
            default -> throw new IllegalArgumentException("old layout " + oldLayout);
        }
        int dstStage;
        int dstAccess;
        switch (newLayout) {
            case VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
                dstAccess = VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
            }
            case VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> {
                dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
                dstAccess = VK10.VK_ACCESS_TRANSFER_READ_BIT;
            }
            case VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                dstStage = SHADER_STAGES;
                dstAccess = VK10.VK_ACCESS_SHADER_READ_BIT;
            }
            default -> throw new IllegalArgumentException("new layout " + newLayout);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(srcAccess)
                .dstAccessMask(dstAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(image);
            barrier.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(baseMip)
                .levelCount(levelCount)
                .baseArrayLayer(0)
                .layerCount(1);
            VK10.vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    /** Copies a tightly packed w x h block from {@code buffer} at {@code offset} into level {@code level} (image in TRANSFER_DST). */
    static void copyBufferToImage(VkCommandBuffer cb, long buffer, long offset, long image, int level, int x, int y, int w, int h) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(offset)
                .bufferRowLength(0)
                .bufferImageHeight(0);
            region.imageSubresource()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(level)
                .baseArrayLayer(0)
                .layerCount(1);
            region.imageOffset().set(x, y, 0);
            region.imageExtent().set(w, h, 1);
            VK10.vkCmdCopyBufferToImage(cb, buffer, image, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }
    }

    /** Copies {@code regions} (already filled, limit = region count) from {@code buffer} into {@code image} (in TRANSFER_DST). */
    static void copyBufferToImageRegions(VkCommandBuffer cb, long buffer, long image, VkBufferImageCopy.Buffer regions) {
        VK10.vkCmdCopyBufferToImage(cb, buffer, image, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions);
    }

    /** Execution + memory dependency between two transfer writes (overlapping copy regions of one image). */
    static void transferWriteBarrier(VkCommandBuffer cb) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkMemoryBarrier.Buffer barrier = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack)
                .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
            VK10.vkCmdPipelineBarrier(cb, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);
        }
    }

    /**
     * Moves the colour image of an offscreen render target between COLOR_ATTACHMENT_OPTIMAL (where it rests between passes, the layout
     * of the phase-1 render passes) and TRANSFER_SRC_OPTIMAL.
     */
    static void targetBarrier(VkCommandBuffer cb, long image, boolean toTransferSrc) {
        int oldLayout = toTransferSrc ? VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL : VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        int newLayout = toTransferSrc ? VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        int srcStage = toTransferSrc ? VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT : VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
        int srcAccess = toTransferSrc ? VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT : 0;
        int dstStage = toTransferSrc ? VK10.VK_PIPELINE_STAGE_TRANSFER_BIT : VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        int dstAccess = toTransferSrc ? VK10.VK_ACCESS_TRANSFER_READ_BIT
            : VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(srcAccess)
                .dstAccessMask(dstAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(image);
            barrier.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    /**
     * Copies the GL rectangle (bottom-left origin, {@code srcHeight} rows) {@code sx0..sx1 x sy0..sy1} of {@code srcImage} (in
     * TRANSFER_SRC) into level {@code dstLevel} of {@code dstImage} (in TRANSFER_DST) at ({@code dx0}, {@code dy0}). GL's bottom-up rows
     * become top-down texture rows, so destination row j holds GL row sy0 + j (the vertical flip of glCopyTexSubImage2D).
     */
    static void blitFlipped(VkCommandBuffer cb, long srcImage, int srcHeight, int sx0, int sy0, int sx1, int sy1, long dstImage,
                            int dstLevel, int dx0, int dy0) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.srcOffsets(0).set(sx0, srcHeight - sy0, 0);
            region.srcOffsets(1).set(sx1, srcHeight - sy1, 1);
            region.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(dstLevel).baseArrayLayer(0).layerCount(1);
            region.dstOffsets(0).set(dx0, dy0, 0);
            region.dstOffsets(1).set(dx0 + (sx1 - sx0), dy0 + (sy1 - sy0), 1);
            VK10.vkCmdBlitImage(cb, srcImage, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dstImage, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                region, VK10.VK_FILTER_NEAREST);
        }
    }

    /** Copies the whole level {@code level} (w x h) into {@code buffer} at {@code offset}, tightly packed (image in TRANSFER_SRC). */
    static void copyImageToBuffer(VkCommandBuffer cb, long image, int level, int w, int h, long buffer, long offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(offset)
                .bufferRowLength(0)
                .bufferImageHeight(0);
            region.imageSubresource()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(level)
                .baseArrayLayer(0)
                .layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(w, h, 1);
            VK10.vkCmdCopyImageToBuffer(cb, image, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);
        }
    }

    /** Makes the transfer writes into {@code buffer} visible to the host (after the fence wait of RenderCore.flushAndWait). */
    static void hostReadBarrier(VkCommandBuffer cb, long buffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack)
                .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(buffer)
                .offset(0L)
                .size(VK10.VK_WHOLE_SIZE);
            VK10.vkCmdPipelineBarrier(cb, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
        }
    }

    /** Clears {@code levelCount} levels to a constant colour (image in TRANSFER_DST). */
    static void clearColor(VkCommandBuffer cb, long image, int baseMip, int levelCount, float r, float g, float b, float a) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue color = VkClearColorValue.calloc(stack);
            color.float32(0, r).float32(1, g).float32(2, b).float32(3, a);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(baseMip)
                .levelCount(levelCount)
                .baseArrayLayer(0)
                .layerCount(1);
            VK10.vkCmdClearColorImage(cb, image, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, range);
        }
    }

    /** Copies levels 0..levels-1 of a w x h (level 0) image into another image of the same format (src TRANSFER_SRC, dst TRANSFER_DST). */
    static void copyImageLevels(VkCommandBuffer cb, long srcImage, long dstImage, int levels, int w, int h) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCopy.Buffer regions = VkImageCopy.calloc(levels, stack);
            for (int l = 0; l < levels; l++) {
                VkImageCopy region = regions.get(l);
                region.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(l).baseArrayLayer(0).layerCount(1);
                region.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(l).baseArrayLayer(0).layerCount(1);
                region.extent().set(Math.max(1, w >> l), Math.max(1, h >> l), 1);
            }
            VK10.vkCmdCopyImage(cb, srcImage, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dstImage,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions);
        }
    }

    /** One linearly filtered blit between two levels of the same image (src TRANSFER_SRC, dst TRANSFER_DST). */
    private static void blitLevel(VkCommandBuffer cb, long image, int srcLevel, int srcW, int srcH, int dstLevel, int dstW, int dstH) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(srcLevel).baseArrayLayer(0).layerCount(1);
            region.srcOffsets(0).set(0, 0, 0);
            region.srcOffsets(1).set(srcW, srcH, 1);
            region.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(dstLevel).baseArrayLayer(0).layerCount(1);
            region.dstOffsets(0).set(0, 0, 0);
            region.dstOffsets(1).set(dstW, dstH, 1);
            VK10.vkCmdBlitImage(cb, image, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, image, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                region, VK10.VK_FILTER_LINEAR);
        }
    }

    /**
     * Generates levels 1..levels-1 from level 0 with a chain of linear blits. All levels must rest in SHADER_READ_ONLY on entry and
     * are left in SHADER_READ_ONLY.
     */
    static void generateMipmaps(VkCommandBuffer cb, long image, int levels, int w, int h) {
        if (levels <= 1) {
            return;
        }
        barrier(cb, image, 0, 1, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        barrier(cb, image, 1, levels - 1, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        int srcW = w;
        int srcH = h;
        for (int i = 1; i < levels; i++) {
            int dstW = Math.max(1, srcW >> 1);
            int dstH = Math.max(1, srcH >> 1);
            blitLevel(cb, image, i - 1, srcW, srcH, i, dstW, dstH);
            if (i < levels - 1) {
                // level i becomes the source of the next blit
                barrier(cb, image, i, 1, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            }
            srcW = dstW;
            srcH = dstH;
        }
        barrier(cb, image, 0, levels - 1, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        barrier(cb, image, levels - 1, 1, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }
}
