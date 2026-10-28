package net.xiaoyu233.fml.vulkan.render.state;

import net.xiaoyu233.fml.vulkan.core.DeviceInfo;
import net.xiaoyu233.fml.vulkan.core.RenderCore;

/**
 * The strings the game shows for {@code glGetString(GL_VENDOR / GL_RENDERER / GL_VERSION)} (D7), taken from the selected Vulkan
 * device. Used by the native {@code CallableGLInfo} (crash report / debug screen).
 */
public final class GlStrings {
    public static final int GL_VENDOR = 7936, GL_RENDERER = 7937, GL_VERSION = 7938;

    private GlStrings() {
    }

    /** The string for a {@code glGetString} name; "Unknown" for any other name. */
    public static String get(int name) {
        return switch (name) {
            case GL_VENDOR -> vendor();
            case GL_RENDERER -> renderer();
            case GL_VERSION -> version();
            default -> "Unknown";
        };
    }

    public static String vendor() {
        DeviceInfo d = RenderCore.deviceInfo();
        return d != null && d.vendorName() != null ? d.vendorName() : "FishModLoader";
    }

    public static String renderer() {
        DeviceInfo d = RenderCore.deviceInfo();
        if (d == null) {
            return "FML Vulkan (not initialized)";
        }
        return d.deviceName() + " (FML Vulkan" + (d.isMoltenVK() ? "/MoltenVK" : "") + ")";
    }

    /** {@code "Vulkan <major>.<minor>.<patch> (<driver>)"}. */
    public static String version() {
        DeviceInfo d = RenderCore.deviceInfo();
        if (d == null) {
            return "Vulkan";
        }
        int v = d.apiVersion();
        String driver = d.driverInfo();
        return "Vulkan " + ((v >>> 22) & 0x7F) + "." + ((v >>> 12) & 0x3FF) + "." + (v & 0xFFF) + (driver == null || driver.isEmpty() ? "" : " (" + driver + ")");
    }
}
