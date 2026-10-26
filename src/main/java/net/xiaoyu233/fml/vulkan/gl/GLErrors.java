package net.xiaoyu233.fml.vulkan.gl;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanConfig;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** The GL error flag behind {@code glGetError}: like a non-distributed GL implementation it holds only the first error until it is read. */
public final class GLErrors {
    private static final AtomicInteger FLAG = new AtomicInteger();
    /** Debug mode: error + call site combinations already logged (each is logged once). */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    private static final int MAX_LOGGED = 40;

    private GLErrors() {
    }

    /** Records {@code glError} unless an error is already pending; GL_NO_ERROR (0) is ignored. */
    public static void set(int glError) {
        if (glError != 0) {
            if (VulkanConfig.debug() && LOGGED.size() < MAX_LOGGED) {
                logOrigin(glError);
            }
            FLAG.compareAndSet(0, glError);
        }
    }

    /** {@code fml.vulkan.debug}: names the GL call (and its callers) that raised an error, once per distinct origin. */
    private static void logOrigin(int glError) {
        String origin = StackWalker.getInstance().walk(frames -> frames
                .skip(1)
                .limit(6)
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber())
                .collect(Collectors.joining(" < ")));
        if (LOGGED.add(glError + " " + origin)) {
            VkLog.LOGGER.warn("GL error 0x{} raised at {}", Integer.toHexString(glError), origin);
        }
    }

    /** Returns the pending error (0 = GL_NO_ERROR) and resets the flag. */
    public static int getAndClear() {
        return FLAG.getAndSet(0);
    }
}
