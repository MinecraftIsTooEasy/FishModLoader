package net.xiaoyu233.fml.vulkan.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The offscreen "default framebuffer": colour + depth image, their views and the framebuffer. All images are created UNDEFINED; the
 * caller initialises them (clear pass) before the first use. Destruction is immediate: the caller makes sure the GPU is done with the
 * target ({@link RenderCore} defers the destruction of a replaced target until the frames that could use it have completed).
 */
final class OffscreenTarget {
    final int width;
    final int height;

    long colorImage;
    long colorAllocation;
    long colorView;
    long depthImage;
    long depthAllocation;
    long depthView;
    long framebuffer;

    private OffscreenTarget(int width, int height) {
        this.width = width;
        this.height = height;
    }

    static OffscreenTarget create(int width, int height, int colorFormat, int depthFormat, long renderPass) {
        OffscreenTarget target = new OffscreenTarget(width, height);
        try {
            target.build(colorFormat, depthFormat, renderPass);
            return target;
        } catch (Throwable t) {
            target.destroy();
            throw t;
        }
    }

    private void build(int colorFormat, int depthFormat, long renderPass) {
        VkDevice device = RenderCore.device();
        long[] image = new long[1];
        long[] allocation = new long[1];

        // The colour image is read by blits (present, glCopyTexSubImage2D), buffer copies (glReadPixels, capture) and may be sampled
        // by future post-processing. TRANSFER_DST keeps transfer writes possible (vkCmdClearColorImage fallback, glDrawPixels-style
        // uploads); Vulkan 1.1 requires it for R8G8B8A8 / B8G8R8A8, it is only left out if a driver does not report it.
        int colorUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        if (DeviceSetup.optimalFeatures(RenderCore.physicalDevice(), colorFormat, VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT)) {
            colorUsage |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        }
        createImage(colorFormat, colorUsage, image, allocation);
        colorImage = image[0];
        colorAllocation = allocation[0];
        VkUtil.setObjectName(device, VK_OBJECT_TYPE_IMAGE, colorImage, "Offscreen color");

        createImage(depthFormat, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, image, allocation);
        depthImage = image[0];
        depthAllocation = allocation[0];
        VkUtil.setObjectName(device, VK_OBJECT_TYPE_IMAGE, depthImage, "Offscreen depth");

        colorView = createView(device, colorImage, colorFormat, VK_IMAGE_ASPECT_COLOR_BIT);
        int depthAspect = VK_IMAGE_ASPECT_DEPTH_BIT | (DeviceSetup.hasStencil(depthFormat) ? VK_IMAGE_ASPECT_STENCIL_BIT : 0);
        depthView = createView(device, depthImage, depthFormat, depthAspect);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer attachments = stack.longs(colorView, depthView); // position 0, limit 2: attachmentCount = 2
            VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .pAttachments(attachments)
                    .width(width)
                    .height(height)
                    .layers(1);
            LongBuffer pFramebuffer = stack.mallocLong(1);
            VkUtil.check(vkCreateFramebuffer(device, info, null, pFramebuffer), "vkCreateFramebuffer");
            framebuffer = pFramebuffer.get(0);
        }
    }

    private void createImage(int format, int usage, long[] outImage, long[] outAllocation) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(width, height, 1);

            // render targets get their own memory block (cheap to free and re-create on resize, preferred by most drivers)
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE)
                    .flags(Vma.VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT);

            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            VkUtil.check(Vma.vmaCreateImage(RenderCore.allocator(), imageInfo, allocationInfo, pImage, pAllocation, null),
                    "vmaCreateImage (" + width + "x" + height + ", format " + format + ")");
            outImage[0] = pImage.get(0);
            outAllocation[0] = pAllocation.get(0);
        }
    }

    private static long createView(VkDevice device, long image, int format, int aspectMask) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            info.subresourceRange()
                    .aspectMask(aspectMask)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            VkUtil.check(vkCreateImageView(device, info, null, pView), "vkCreateImageView");
            return pView.get(0);
        }
    }

    /** Null/zero safe. */
    void destroy() {
        VkDevice device = RenderCore.device();
        long vma = RenderCore.allocator();
        if (framebuffer != 0) {
            vkDestroyFramebuffer(device, framebuffer, null);
            framebuffer = 0;
        }
        if (depthView != 0) {
            vkDestroyImageView(device, depthView, null);
            depthView = 0;
        }
        if (colorView != 0) {
            vkDestroyImageView(device, colorView, null);
            colorView = 0;
        }
        if (depthImage != 0) {
            Vma.vmaDestroyImage(vma, depthImage, depthAllocation);
            depthImage = 0;
        }
        if (colorImage != 0) {
            Vma.vmaDestroyImage(vma, colorImage, colorAllocation);
            colorImage = 0;
        }
    }
}
