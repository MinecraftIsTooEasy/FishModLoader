package net.xiaoyu233.fml.vulkan.gl;

import net.xiaoyu233.fml.vulkan.core.DeviceInfo;
import net.xiaoyu233.fml.vulkan.core.RenderCore;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the emulated GL context advertises: the capability flags behind {@code org.lwjgl.opengl.GLContext.getCapabilities()},
 * the {@code GL_EXTENSIONS} string and the vendor/renderer/version strings. The flag set depends on the selected device
 * (anisotropic filtering), so it is only cached once {@link RenderCore#isInitialized()}; {@link #reset()} (called from
 * {@code GLState.init()}) drops the caches when the backend is (re)started.
 */
public final class GLCapabilities {
    private static final String[] BASE = {
            "OpenGL11", "OpenGL12", "OpenGL13", "OpenGL14", "OpenGL15",
            "GL_ARB_multitexture",
            "GL_ARB_texture_env_combine",
            "GL_ARB_texture_env_add",
            "GL_EXT_texture_env_add",
            "GL_ARB_texture_non_power_of_two",
            "GL_ARB_vertex_buffer_object",
            "GL_ARB_transpose_matrix",
            "GL_ARB_texture_border_clamp",
            "GL_ARB_texture_mirrored_repeat",
            "GL_EXT_texture_edge_clamp",
            "GL_SGIS_texture_edge_clamp",
            "GL_EXT_blend_func_separate",
            "GL_EXT_blend_color",
            "GL_EXT_blend_minmax",
            "GL_EXT_blend_subtract",
            "GL_EXT_blend_equation_separate",
            "GL_EXT_bgra",
            "GL_EXT_abgr",
            "GL_EXT_packed_pixels",
            "GL_EXT_rescale_normal",
            "GL_EXT_draw_range_elements",
            "GL_EXT_multi_draw_arrays",
            "GL_EXT_texture_lod_bias",
            "GL_NV_fog_distance",
            "GL_SGIS_generate_mipmap",
    };

    private static volatile Set<String> cachedSet;
    private static volatile String cachedExtensions;
    private static volatile String cachedVersion;
    private static volatile int generation;

    private GLCapabilities() {
    }

    /** True once the capability set reflects the real device (the caches are only filled from then on). */
    public static boolean ready() {
        return RenderCore.isInitialized();
    }

    /** Bumped by {@link #reset()}; lets {@code GLContext} notice that its cached ContextCapabilities are stale. */
    public static int generation() {
        return generation;
    }

    /** Drops every cache: the device (and with it anisotropy support) may differ after a backend restart. */
    public static void reset() {
        cachedSet = null;
        cachedExtensions = null;
        cachedVersion = null;
        generation++;
    }

    /**
     * Names of the supported ContextCapabilities flags ("OpenGL11".., "GL_ARB_multitexture"..), immutable and in a stable order.
     * GL 2.0+ programs, FBOs and occlusion queries are deliberately absent (see ARCHITECTURE section 1).
     */
    public static Set<String> supported() {
        Set<String> set = cachedSet;
        if (set != null) {
            return set;
        }
        final boolean ready = ready();
        final LinkedHashSet<String> caps = new LinkedHashSet<>(Arrays.asList(BASE));
        if (ready) {
            final DeviceInfo di = RenderCore.deviceInfo();
            if (di != null && di.samplerAnisotropy()) {
                caps.add("GL_EXT_texture_filter_anisotropic");
            }
        }
        set = Collections.unmodifiableSet(caps);
        if (ready) {
            cachedSet = set;
        }
        return set;
    }

    /** The GL_EXTENSIONS string: the extension flags of {@link #supported()} without the OpenGLxx version markers. */
    public static String extensions() {
        String s = cachedExtensions;
        if (s != null) {
            return s;
        }
        final StringBuilder sb = new StringBuilder();
        for (String name : supported()) {
            if (name.startsWith("OpenGL")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(name);
        }
        s = sb.toString();
        if (ready()) {
            cachedExtensions = s;
        }
        return s;
    }

    public static String vendor() {
        final DeviceInfo di = ready() ? RenderCore.deviceInfo() : null;
        return di != null && di.vendorName() != null ? di.vendorName() : "FishModLoader";
    }

    public static String renderer() {
        final DeviceInfo di = ready() ? RenderCore.deviceInfo() : null;
        if (di == null) {
            return "FML Vulkan (not initialized)";
        }
        return di.deviceName() + " (FML Vulkan" + (di.isMoltenVK() ? "/MoltenVK" : "") + ")";
    }

    /** {@code "1.5.0 FML-Vulkan <major>.<minor>.<patch>"}: the emulated GL version plus the Vulkan API version of the device. */
    public static String version() {
        String s = cachedVersion;
        if (s != null) {
            return s;
        }
        final DeviceInfo di = ready() ? RenderCore.deviceInfo() : null;
        if (di == null) {
            return "1.5.0 FML-Vulkan";
        }
        final int v = di.apiVersion();
        s = "1.5.0 FML-Vulkan " + ((v >>> 22) & 0x7F) + "." + ((v >>> 12) & 0x3FF) + "." + (v & 0xFFF);
        cachedVersion = s;
        return s;
    }
}
