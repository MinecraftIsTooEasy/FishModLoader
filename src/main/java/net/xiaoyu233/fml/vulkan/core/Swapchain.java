package net.xiaoyu233.fml.vulkan.core;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The window swapchain. Images are only ever blit destinations (the game renders into the offscreen target), so no image views or
 * framebuffers exist. One "render finished" semaphore per swapchain image (not per frame slot) is created and destroyed together with
 * the swapchain.
 */
final class Swapchain {
    final long handle;
    final int format;
    final int width;
    final int height;
    final int presentMode;
    final long[] images;
    final long[] renderFinished;
    /** Result of the last successful {@link #acquire}. */
    int lastImageIndex;

    private final VkDevice device;

    private Swapchain(VkDevice device, long handle, int format, int width, int height, int presentMode, long[] images, long[] renderFinished) {
        this.device = device;
        this.handle = handle;
        this.format = format;
        this.width = width;
        this.height = height;
        this.presentMode = presentMode;
        this.images = images;
        this.renderFinished = renderFinished;
    }

    /**
     * Creates a swapchain for the current surface size (the surface decides; {@code fallbackWidth/Height} are only used when the
     * surface lets the swapchain choose). {@code old} (may be null) is passed as oldSwapchain and must be {@link #destroy destroyed}
     * by the caller afterwards. Returns null while the surface has no drawable area (minimised window).
     */
    static Swapchain create(long surface, int fallbackWidth, int fallbackHeight, boolean vsync, Swapchain old) {
        VkDevice device = RenderCore.device();
        VkPhysicalDevice physicalDevice = RenderCore.physicalDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            VkUtil.check(KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, caps),
                    "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");

            int width = caps.currentExtent().width();
            int height = caps.currentExtent().height();
            if (width == -1) { // 0xFFFFFFFF: the swapchain size defines the surface size
                width = Math.max(caps.minImageExtent().width(), Math.min(caps.maxImageExtent().width(), fallbackWidth));
                height = Math.max(caps.minImageExtent().height(), Math.min(caps.maxImageExtent().height(), fallbackHeight));
            }
            if (width <= 0 || height <= 0) {
                return null;
            }

            int minImages = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0) {
                minImages = Math.min(minImages, caps.maxImageCount());
            }

            int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            usage &= caps.supportedUsageFlags();
            if ((usage & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
                throw new IllegalStateException("The window surface does not support TRANSFER_DST swapchain images (blit)");
            }

            int compositeAlpha = KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
            int supportedAlpha = caps.supportedCompositeAlpha();
            if ((supportedAlpha & compositeAlpha) == 0) {
                compositeAlpha = Integer.lowestOneBit(supportedAlpha); // INHERIT etc.; the alpha channel is irrelevant for the game
            }

            IntBuffer pCount = stack.mallocInt(1);
            VkUtil.check(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, pCount, null), "vkGetPhysicalDeviceSurfaceFormatsKHR");
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(pCount.get(0), stack);
            VkUtil.check(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, pCount, formats), "vkGetPhysicalDeviceSurfaceFormatsKHR");
            int formatIndex = chooseFormat(physicalDevice, formats, pCount.get(0));
            int format = formats.get(formatIndex).format();
            int colorSpace = formats.get(formatIndex).colorSpace();

            VkUtil.check(KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, pCount, null), "vkGetPhysicalDeviceSurfacePresentModesKHR");
            IntBuffer modes = stack.mallocInt(pCount.get(0));
            VkUtil.check(KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, pCount, modes), "vkGetPhysicalDeviceSurfacePresentModesKHR");
            int presentMode = choosePresentMode(modes, pCount.get(0), vsync);

            VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(surface)
                    .minImageCount(minImages)
                    .imageFormat(format)
                    .imageColorSpace(colorSpace)
                    .imageArrayLayers(1)
                    .imageUsage(usage)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(caps.currentTransform())
                    .compositeAlpha(compositeAlpha)
                    .presentMode(presentMode)
                    .clipped(true)
                    .oldSwapchain(old != null ? old.handle : VK_NULL_HANDLE);
            info.imageExtent().set(width, height);

            LongBuffer pSwapchain = stack.mallocLong(1);
            VkUtil.check(KHRSwapchain.vkCreateSwapchainKHR(device, info, null, pSwapchain), "vkCreateSwapchainKHR");
            long swapchain = pSwapchain.get(0);

            long[] images;
            long[] semaphores;
            try {
                VkUtil.check(KHRSwapchain.vkGetSwapchainImagesKHR(device, swapchain, pCount, null), "vkGetSwapchainImagesKHR");
                LongBuffer pImages = stack.mallocLong(pCount.get(0));
                VkUtil.check(KHRSwapchain.vkGetSwapchainImagesKHR(device, swapchain, pCount, pImages), "vkGetSwapchainImagesKHR");
                images = new long[pCount.get(0)];
                pImages.get(images);

                semaphores = new long[images.length];
                VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
                LongBuffer pSemaphore = stack.mallocLong(1);
                for (int i = 0; i < semaphores.length; i++) {
                    VkUtil.check(vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore), "vkCreateSemaphore (renderFinished)");
                    semaphores[i] = pSemaphore.get(0);
                }
            } catch (Throwable t) {
                KHRSwapchain.vkDestroySwapchainKHR(device, swapchain, null);
                throw t;
            }
            VkLog.LOGGER.info("Swapchain {}x{}, {} images, format {}, present mode {}{}", width, height, images.length,
                    format, presentModeName(presentMode), vsync ? " (vsync)" : "");
            Swapchain result = new Swapchain(device, swapchain, format, width, height, presentMode, images, semaphores);
            for (int i = 0; i < images.length; i++) {
                VkUtil.setObjectName(device, VK_OBJECT_TYPE_IMAGE, images[i], "Swapchain image " + i);
            }
            return result;
        }
    }

    /** B8G8R8A8_UNORM + SRGB_NONLINEAR first (no sRGB conversion, like GL), then other 8-bit UNORM formats that can be blitted to. */
    private static int chooseFormat(VkPhysicalDevice physicalDevice, VkSurfaceFormatKHR.Buffer formats, int count) {
        int[] preferred = {VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM};
        for (int wanted : preferred) {
            for (int i = 0; i < count; i++) {
                if (formats.get(i).format() == wanted && formats.get(i).colorSpace() == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
                        && DeviceSetup.optimalFeatures(physicalDevice, wanted, VK_FORMAT_FEATURE_BLIT_DST_BIT)) {
                    return i;
                }
            }
        }
        int[] other = {VK_FORMAT_A8B8G8R8_UNORM_PACK32, VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM};
        for (int wanted : other) {
            for (int i = 0; i < count; i++) {
                if (formats.get(i).format() == wanted && DeviceSetup.optimalFeatures(physicalDevice, wanted, VK_FORMAT_FEATURE_BLIT_DST_BIT)) {
                    return i;
                }
            }
        }
        for (int i = 0; i < count; i++) {
            if (DeviceSetup.optimalFeatures(physicalDevice, formats.get(i).format(), VK_FORMAT_FEATURE_BLIT_DST_BIT)) {
                VkLog.LOGGER.warn("No UNORM swapchain format available, using format {}", formats.get(i).format());
                return i;
            }
        }
        throw new IllegalStateException("No swapchain format of the window surface supports being a blit destination");
    }

    /** auto: vsync -> FIFO, else MAILBOX -> IMMEDIATE -> FIFO; explicit modes fall back to FIFO when unsupported. */
    private static int choosePresentMode(IntBuffer modes, int count, boolean vsync) {
        boolean mailbox = false;
        boolean immediate = false;
        for (int i = 0; i < count; i++) {
            int mode = modes.get(i);
            mailbox |= mode == KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
            immediate |= mode == KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
        }
        String configured = VulkanConfig.presentMode();
        switch (configured) {
            case "fifo":
                return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
            case "mailbox":
                if (mailbox) {
                    return KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
                }
                VkLog.LOGGER.warn("Present mode mailbox is not supported by this surface, using FIFO");
                return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
            case "immediate":
                if (immediate) {
                    return KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
                }
                VkLog.LOGGER.warn("Present mode immediate is not supported by this surface, using FIFO");
                return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
            default:
                if (vsync) {
                    return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
                }
                if (mailbox) {
                    return KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
                }
                return immediate ? KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR : KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
        }
    }

    private static String presentModeName(int mode) {
        return switch (mode) {
            case KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE";
            case KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX";
            case KHRSurface.VK_PRESENT_MODE_FIFO_KHR -> "FIFO";
            case KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO_RELAXED";
            default -> "mode " + mode;
        };
    }

    /** Raw VkResult; on SUCCESS / SUBOPTIMAL {@link #lastImageIndex} is the acquired image and {@code semaphore} will be signalled. */
    int acquire(long semaphore, long timeoutNanos) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pIndex = stack.mallocInt(1);
            int result = KHRSwapchain.vkAcquireNextImageKHR(device, handle, timeoutNanos, semaphore, VK_NULL_HANDLE, pIndex);
            lastImageIndex = pIndex.get(0);
            return result;
        }
    }

    /** Raw VkResult of vkQueuePresentKHR, waiting for this image's render-finished semaphore. */
    int present(VkQueue queue, int imageIndex) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPresentInfoKHR info = VkPresentInfoKHR.calloc(stack)
                    .sType(KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(renderFinished[imageIndex]))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(handle))
                    .pImageIndices(stack.ints(imageIndex));
            return KHRSwapchain.vkQueuePresentKHR(queue, info);
        }
    }

    /** Current size of the surface as reported by the driver, or null if it cannot be queried; used to ignore bogus SUBOPTIMAL. */
    int[] currentSurfaceExtent(long surface) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            int result = KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(RenderCore.physicalDevice(), surface, caps);
            if (result < 0) {
                return null;
            }
            return new int[]{caps.currentExtent().width(), caps.currentExtent().height()};
        }
    }

    /** The device must be idle (or at least done with this swapchain's images and semaphores). */
    void destroy() {
        for (int i = 0; i < renderFinished.length; i++) {
            if (renderFinished[i] != 0) {
                vkDestroySemaphore(device, renderFinished[i], null);
                renderFinished[i] = 0;
            }
        }
        KHRSwapchain.vkDestroySwapchainKHR(device, handle, null);
    }
}
