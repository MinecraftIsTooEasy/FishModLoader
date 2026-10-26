package net.xiaoyu233.fml.vulkan.core;

/**
 * Immutable snapshot of the selected physical device (filled by module A, read by D1 for {@code glGetString} / limits, B for
 * anisotropy and texture size, C for the optional pipeline features).
 *
 * @param vendorName                      e.g. "Apple" (VkPhysicalDeviceProperties vendorID decoded)
 * @param deviceName                      VkPhysicalDeviceProperties deviceName
 * @param driverInfo                      driver name/version text (VkPhysicalDeviceDriverProperties when available)
 * @param apiVersion                      VkPhysicalDeviceProperties apiVersion (VK_MAKE_VERSION encoding)
 * @param isMoltenVK                      the device runs through MoltenVK / VK_KHR_portability_subset
 * @param wideLines                       VkPhysicalDeviceFeatures wideLines enabled
 * @param fillModeNonSolid                VkPhysicalDeviceFeatures fillModeNonSolid enabled
 * @param samplerAnisotropy               VkPhysicalDeviceFeatures samplerAnisotropy enabled
 * @param maxAnisotropy                   VkPhysicalDeviceLimits maxSamplerAnisotropy
 * @param logicOp                         VkPhysicalDeviceFeatures logicOp enabled
 * @param maxImageDimension2D             VkPhysicalDeviceLimits maxImageDimension2D
 * @param minUniformBufferOffsetAlignment VkPhysicalDeviceLimits minUniformBufferOffsetAlignment
 * @param lineWidthRange                  VkPhysicalDeviceLimits lineWidthRange {min, max}
 * @param pointSizeRange                  VkPhysicalDeviceLimits pointSizeRange {min, max}
 * @param portabilityImageViewFormatSwizzle VkPhysicalDevicePortabilitySubsetFeaturesKHR imageViewFormatSwizzle enabled (always true on a
 *                                        device without VK_KHR_portability_subset): image views may remap components (alpha = ONE)
 * @param portabilitySamplerMipLodBias    VkPhysicalDevicePortabilitySubsetFeaturesKHR samplerMipLodBias enabled (always true on a device
 *                                        without VK_KHR_portability_subset): VkSamplerCreateInfo.mipLodBias may be non-zero
 */
public record DeviceInfo(String vendorName, String deviceName, String driverInfo, int apiVersion, boolean isMoltenVK,
                         boolean wideLines, boolean fillModeNonSolid, boolean samplerAnisotropy, float maxAnisotropy,
                         boolean logicOp, int maxImageDimension2D, long minUniformBufferOffsetAlignment,
                         float[] lineWidthRange, float[] pointSizeRange, boolean portabilityImageViewFormatSwizzle,
                         boolean portabilitySamplerMipLodBias) {
    public DeviceInfo {
        if (lineWidthRange == null || lineWidthRange.length != 2 || pointSizeRange == null || pointSizeRange.length != 2) {
            throw new IllegalArgumentException("lineWidthRange and pointSizeRange must have exactly 2 elements");
        }
    }
}
