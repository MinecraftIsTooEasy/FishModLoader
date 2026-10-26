package net.xiaoyu233.fml.vulkan.core;

import net.xiaoyu233.fml.vulkan.VkLog;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDebugUtilsLabelEXT;
import org.lwjgl.vulkan.VkDebugUtilsObjectNameInfoEXT;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import static org.lwjgl.vulkan.VK10.*;

/** Small stateless Vulkan helpers shared by the core classes (result checking, barriers, debug names). */
final class VkUtil {
    /** UINT64_MAX as a Java long (timeouts). */
    static final long UINT64_MAX = -1L;

    /** True when VK_EXT_debug_utils was enabled on the instance (set by {@link RenderCore}; debug mode / validation). */
    static boolean debugUtils;

    private VkUtil() {
    }

    /** Failed Vulkan call; the numeric result stays available for callers that want to react to specific codes. */
    static final class VkException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        final int result;

        VkException(int result, String message) {
            super(message);
            this.result = result;
        }
    }

    /** Throws for every error result (negative VkResult); success and status codes (SUBOPTIMAL, INCOMPLETE...) pass. */
    static void check(int result, String operation) {
        if (result >= 0) {
            return;
        }
        String name = resultName(result);
        if (result == VK_ERROR_DEVICE_LOST) {
            VkLog.LOGGER.fatal("Vulkan device lost during {} ({}): the GPU driver crashed, hung or the device was removed. "
                    + "The game cannot continue rendering, please restart it (update the graphics driver if this repeats).", operation, name);
        } else if (result == VK_ERROR_OUT_OF_DEVICE_MEMORY || result == VK_ERROR_OUT_OF_HOST_MEMORY) {
            VkLog.LOGGER.error("Vulkan ran out of {} memory during {} ({})",
                    result == VK_ERROR_OUT_OF_DEVICE_MEMORY ? "device (GPU)" : "host", operation, name);
        }
        throw new VkException(result, operation + " failed: " + name + " (VkResult " + result + ")"
                + (result == VK_ERROR_DEVICE_LOST ? " - Vulkan device lost, fatal" : ""));
    }

    static String resultName(int result) {
        return switch (result) {
            case VK_SUCCESS -> "VK_SUCCESS";
            case VK_NOT_READY -> "VK_NOT_READY";
            case VK_TIMEOUT -> "VK_TIMEOUT";
            case VK_EVENT_SET -> "VK_EVENT_SET";
            case VK_EVENT_RESET -> "VK_EVENT_RESET";
            case VK_INCOMPLETE -> "VK_INCOMPLETE";
            case VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
            case VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
            case VK_ERROR_MEMORY_MAP_FAILED -> "VK_ERROR_MEMORY_MAP_FAILED";
            case VK_ERROR_LAYER_NOT_PRESENT -> "VK_ERROR_LAYER_NOT_PRESENT";
            case VK_ERROR_EXTENSION_NOT_PRESENT -> "VK_ERROR_EXTENSION_NOT_PRESENT";
            case VK_ERROR_FEATURE_NOT_PRESENT -> "VK_ERROR_FEATURE_NOT_PRESENT";
            case VK_ERROR_INCOMPATIBLE_DRIVER -> "VK_ERROR_INCOMPATIBLE_DRIVER";
            case VK_ERROR_TOO_MANY_OBJECTS -> "VK_ERROR_TOO_MANY_OBJECTS";
            case VK_ERROR_FORMAT_NOT_SUPPORTED -> "VK_ERROR_FORMAT_NOT_SUPPORTED";
            case VK_ERROR_FRAGMENTED_POOL -> "VK_ERROR_FRAGMENTED_POOL";
            case VK_ERROR_UNKNOWN -> "VK_ERROR_UNKNOWN";
            case KHRSurface.VK_ERROR_SURFACE_LOST_KHR -> "VK_ERROR_SURFACE_LOST_KHR";
            case KHRSurface.VK_ERROR_NATIVE_WINDOW_IN_USE_KHR -> "VK_ERROR_NATIVE_WINDOW_IN_USE_KHR";
            case KHRSwapchain.VK_SUBOPTIMAL_KHR -> "VK_SUBOPTIMAL_KHR";
            case KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR -> "VK_ERROR_OUT_OF_DATE_KHR";
            default -> "VkResult(" + result + ")";
        };
    }

    static int alignUp(int value, int alignment) {
        return (value + alignment - 1) & -alignment;
    }

    static long alignUp(long value, long alignment) {
        return (value + alignment - 1) & -alignment;
    }

    /** Single image barrier (whole image, mip 0, layer 0). Call with the layouts the image really has. */
    static void imageBarrier(VkCommandBuffer cb, long image, int aspectMask, int oldLayout, int newLayout,
                             int srcStage, int srcAccess, int dstStage, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            fillImageBarrier(barrier, image, aspectMask, oldLayout, newLayout, srcAccess, dstAccess);
            vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    /** Fills the element at the current position of {@code barrier} (zeroed by the caller). */
    static void fillImageBarrier(VkImageMemoryBarrier.Buffer barrier, long image, int aspectMask, int oldLayout, int newLayout,
                                 int srcAccess, int dstAccess) {
        barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(srcAccess)
                .dstAccessMask(dstAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange()
                .aspectMask(aspectMask)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
    }

    /** Waits for a fence; warns while a wait takes suspiciously long and gives up (device hang) after 60 s. */
    static void waitFence(VkDevice device, long fence, String what) {
        long waitedMs = 0;
        while (true) {
            int result = vkWaitForFences(device, fence, true, 5_000_000_000L);
            if (result == VK_SUCCESS) {
                return;
            }
            if (result == VK_TIMEOUT) {
                waitedMs += 5000;
                VkLog.LOGGER.warn("Still waiting for the GPU ({}), {} s", what, waitedMs / 1000);
                if (waitedMs >= 60_000) {
                    throw new VkException(VK_TIMEOUT, "The GPU did not finish within 60 s (" + what + "), the device is probably hung");
                }
                continue;
            }
            check(result, "vkWaitForFences (" + what + ")");
            return;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // debug utils (no-ops unless VK_EXT_debug_utils is enabled)
    // ---------------------------------------------------------------------------------------------------------

    static void setObjectName(VkDevice device, int objectType, long handle, String name) {
        if (!debugUtils || handle == 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDebugUtilsObjectNameInfoEXT info = VkDebugUtilsObjectNameInfoEXT.calloc(stack)
                    .sType(EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_OBJECT_NAME_INFO_EXT)
                    .objectType(objectType)
                    .objectHandle(handle)
                    .pObjectName(stack.UTF8(name));
            EXTDebugUtils.vkSetDebugUtilsObjectNameEXT(device, info);
        } catch (Throwable t) {
            debugUtils = false;
            VkLog.LOGGER.debug("Debug utils object naming unavailable", t);
        }
    }

    static void beginLabel(VkCommandBuffer cb, String name) {
        if (!debugUtils) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDebugUtilsLabelEXT label = VkDebugUtilsLabelEXT.calloc(stack)
                    .sType(EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_LABEL_EXT)
                    .pLabelName(stack.UTF8(name));
            label.color(0, 0.2f).color(1, 0.6f).color(2, 1.0f).color(3, 1.0f);
            EXTDebugUtils.vkCmdBeginDebugUtilsLabelEXT(cb, label);
        } catch (Throwable t) {
            debugUtils = false;
            VkLog.LOGGER.debug("Debug utils labels unavailable", t);
        }
    }

    static void endLabel(VkCommandBuffer cb) {
        if (!debugUtils) {
            return;
        }
        try {
            EXTDebugUtils.vkCmdEndDebugUtilsLabelEXT(cb);
        } catch (Throwable t) {
            debugUtils = false;
        }
    }
}
