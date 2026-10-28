package net.xiaoyu233.fml.vulkan.render.gpu;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.core.DeviceCaps;
import net.xiaoyu233.fml.vulkan.core.DeviceInfo;
import net.xiaoyu233.fml.vulkan.core.RenderCore;

/**
 * Optional device capabilities that were really enabled on the logical device (5.6): answers come from the feature chain
 * {@code DeviceSetup} built, not from what the physical device merely offers. Every answer is false before the backend started.
 */
public final class DeviceFeatures {
    private static boolean warnedMdi;

    private DeviceFeatures() {
    }

    public static boolean multiDrawIndirect() {
        return RenderCore.deviceCaps().multiDrawIndirect();
    }

    public static boolean drawIndirectFirstInstance() {
        return RenderCore.deviceCaps().drawIndirectFirstInstance();
    }

    /** VK_EXT_extended_dynamic_state: cull mode, depth test / write / compare op can be dynamic. */
    public static boolean extendedDynamicState() {
        return RenderCore.deviceCaps().extendedDynamicState();
    }

    public static boolean logicOp() {
        DeviceInfo info = RenderCore.deviceInfo();
        return info != null && info.logicOp();
    }

    /** VK_KHR_present_wait together with VK_KHR_present_id (enabled when the device supports both). */
    public static boolean presentWait() {
        return RenderCore.deviceCaps().presentWait();
    }

    /** GPU timestamps of the graphics queue are usable ({@link GpuTimer}). */
    public static boolean timestamps() {
        return RenderCore.deviceCaps().timestamps();
    }

    /** Nanoseconds per timestamp tick; 0 without timestamps. */
    public static float timestampPeriodNs() {
        return RenderCore.deviceCaps().timestampPeriodNs();
    }

    /** Unified memory: host-visible memory is device-local (Apple, integrated GPUs); arenas write straight into mapped pages. */
    public static boolean uma() {
        return RenderCore.isInitialized() && RenderCore.isUnifiedMemory();
    }

    public static boolean wideLines() {
        DeviceInfo info = RenderCore.deviceInfo();
        return info != null && info.wideLines();
    }

    /**
     * Multi-draw-indirect for the terrain as decided by {@code fml.vulkan.mdi}: {@code auto} = on when the device reports both
     * {@link #multiDrawIndirect()} and {@link #drawIndirectFirstInstance()}; {@code on} = the same requirement (forcing it on a device
     * without the features would be invalid) with a warning when unmet; {@code off} = never.
     */
    public static boolean mdiEnabled() {
        String mode = VulkanConfig.mdi();
        if ("off".equals(mode)) {
            return false;
        }
        boolean capable = multiDrawIndirect() && drawIndirectFirstInstance();
        if (!capable && "on".equals(mode) && !warnedMdi) {
            warnedMdi = true;
            VkLog.LOGGER.warn("fml.vulkan.mdi=on but the device lacks multiDrawIndirect / drawIndirectFirstInstance: per-section draws are used");
        }
        return capable;
    }

    /** The capability record (for the debug report). */
    public static DeviceCaps caps() {
        return RenderCore.deviceCaps();
    }
}
