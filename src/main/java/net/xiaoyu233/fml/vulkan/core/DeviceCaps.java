package net.xiaoyu233.fml.vulkan.core;

/**
 * Optional device capabilities that were actually enabled on the logical device (filled by {@code DeviceSetup}, read through
 * {@code RenderCore.deviceCaps()} and {@code render.gpu.DeviceFeatures}).
 *
 * @param multiDrawIndirect         VkPhysicalDeviceFeatures multiDrawIndirect enabled
 * @param drawIndirectFirstInstance VkPhysicalDeviceFeatures drawIndirectFirstInstance enabled
 * @param extendedDynamicState      VK_EXT_extended_dynamic_state enabled (cull mode, depth test / write / compare op are dynamic)
 * @param presentWait               VK_KHR_present_id and VK_KHR_present_wait enabled
 * @param timestampValidBits        valid bits of the timestamps of the graphics queue family (0 = no timestamps)
 * @param timestampPeriodNs         nanoseconds per timestamp tick
 */
public record DeviceCaps(boolean multiDrawIndirect, boolean drawIndirectFirstInstance, boolean extendedDynamicState,
                         boolean presentWait, int timestampValidBits, float timestampPeriodNs) {
    /** Nothing optional enabled. */
    public static final DeviceCaps NONE = new DeviceCaps(false, false, false, false, 0, 0.0f);

    /** True when GPU timestamps of the graphics queue can be used. */
    public boolean timestamps() {
        return timestampValidBits > 0 && timestampPeriodNs > 0.0f;
    }
}
