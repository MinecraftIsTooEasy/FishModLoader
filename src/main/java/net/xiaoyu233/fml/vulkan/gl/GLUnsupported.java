package net.xiaoyu233.fml.vulkan.gl;

import net.xiaoyu233.fml.vulkan.VkLog;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One-time diagnostics for GL entry points that are not (yet) backed by the Vulkan emulation. Thread-safe. */
public final class GLUnsupported {
    private static final Set<String> CALLED = ConcurrentHashMap.newKeySet();
    private static final Set<String> TODO = ConcurrentHashMap.newKeySet();

    private GLUnsupported() {
    }

    /** Facade for an LWJGL 2 function we do not implement: logs once (WARN) per function name, otherwise a no-op. */
    public static void call(String function) {
        String name = String.valueOf(function);
        if (CALLED.add(name)) {
            VkLog.LOGGER.warn("Unsupported OpenGL function called (ignored): {}", name);
        }
    }

    /** Placeholder in owner facades that are not implemented yet: logs once (ERROR) per function name. */
    public static void todo(String function) {
        String name = String.valueOf(function);
        if (TODO.add(name)) {
            VkLog.LOGGER.error("OpenGL function is not implemented yet: {}", name);
        }
    }
}
