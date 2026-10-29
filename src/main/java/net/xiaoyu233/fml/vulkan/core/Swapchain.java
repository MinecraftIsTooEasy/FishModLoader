package net.xiaoyu233.fml.vulkan.core;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPresentWait;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPresentIdKHR;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainLatencyCreateInfoNV;
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
    /** VK_KHR_present_id: id of the last present that carried one (0 = none yet). */
    private long lastPresentId;

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

            // image count: max(3, min + 1) so the GPU can queue a frame while one is shown and one is being written (triple buffering);
            // fml.vulkan.latency=low / fml.vulkan.swapchainMinImages: the minimum (or the given count). Clamped to [min, max].
            int minImages = Math.max(3, caps.minImageCount() + 1);
            String minProp = System.getProperty(net.xiaoyu233.fml.vulkan.render.frame.FramePacer.PROP_MIN_SWAPCHAIN);
            if (RenderCore.isLowLatency() || (minProp != null && !minProp.isBlank() && !"false".equalsIgnoreCase(minProp.trim()))) {
                minImages = caps.minImageCount();
                if (minProp != null) {
                    try {
                        int n = Integer.parseInt(minProp.trim());
                        minImages = Math.max(caps.minImageCount(), n);
                    } catch (NumberFormatException ignored) {
                        // "true": minimum
                    }
                }
            }
            if (caps.maxImageCount() > 0) {
                minImages = Math.min(minImages, caps.maxImageCount());
            }
            minImages = Math.max(minImages, caps.minImageCount());

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
            StringBuilder offered = new StringBuilder();
            for (int i = 0; i < pCount.get(0); i++) {
                offered.append(i == 0 ? "" : ",").append(presentModeName(modes.get(i)));
            }

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
            if (LowLatency.nvEnabled()) {
                // Reflex: the swapchain must opt in to latency pacing for vkSetLatencySleepModeNV / vkLatencySleepNV
                info.pNext(VkSwapchainLatencyCreateInfoNV.calloc(stack).sType$Default().latencyModeEnable(true));
            }

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
            VkLog.LOGGER.info("Swapchain {}x{}, {} images (surface min {} max {}), format {}, present mode {}{} (offered: {})", width, height,
                    images.length, caps.minImageCount(), caps.maxImageCount(), format, presentModeName(presentMode), vsync ? " (vsync)" : "", offered);
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

    /** auto: vsync -> FIFO, else IMMEDIATE -> MAILBOX -> FIFO; explicit modes fall back to FIFO when unsupported. */
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
                // vsync off = what GL's swap interval 0 does: IMMEDIATE (every frame reaches the screen, may tear), else MAILBOX (never
                // blocks, the newest frame wins at each refresh), else FIFO. fml.vulkan.presentMode=mailbox picks MAILBOX explicitly.
                if (immediate) {
                    return KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
                }
                if (mailbox) {
                    return KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
                }
                VkLog.LOGGER.warn("Neither IMMEDIATE nor MAILBOX is offered by this surface: vsync off is not possible, FIFO (display paced)");
                return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
        }
    }

    static String presentModeName(int mode) {
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
            long id = 0L;
            boolean reflex = LowLatency.nvEnabled();
            if (reflex) {
                // Reflex: the present carries the frame's id (the same as its markers and its submit), strictly increasing
                id = LowLatency.currentPresentId();
                if (id <= lastPresentId) {
                    id = lastPresentId + 1L; // never reuse an id on this swapchain (VUID-VkPresentIdKHR-presentIds-04999)
                }
            } else if (RenderCore.deviceCaps().presentWait()) {
                id = lastPresentId + 1L;
            }
            if (id != 0L) {
                VkPresentIdKHR presentId = VkPresentIdKHR.calloc(stack)
                        .sType$Default()
                        .swapchainCount(1)
                        .pPresentIds(stack.longs(id));
                info.pNext(presentId);
            }
            LowLatency.presentStart(); // PRESENT_START (NVIDIA) / Anti-Lag present stage (AMD); one boolean test otherwise
            int result = KHRSwapchain.vkQueuePresentKHR(queue, info);
            LowLatency.presentEnd();
            if (id != 0L && result >= 0) {
                lastPresentId = id;
            }
            return result;
        }
    }

    /** VK_KHR_present_wait: waits until the last present with an id was shown; false when there is none, on timeout or on error. */
    boolean waitForLastPresent(long timeoutNanos) {
        long id = lastPresentId;
        if (id == 0L) {
            return false;
        }
        int result = KHRPresentWait.vkWaitForPresentKHR(device, handle, id, timeoutNanos);
        return result == VK_SUCCESS;
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
