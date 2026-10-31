package net.xiaoyu233.fml.vulkan.audio;

import net.xiaoyu233.fml.vulkan.VkLog;

/**
 * Logging of the audio layer. Goes through {@link VkLog}; falls back to stderr when log4j is not usable (unit tests, tools),
 * so audio code never fails because of logging.
 */
final class AudioLog {
    private AudioLog() {
    }

    static void info(String message) {
        try {
            VkLog.LOGGER.info("[OpenAL] {}", message);
        } catch (Throwable t) {
            System.err.println("[FML-OpenAL] " + message);
        }
    }

    static void warn(String message, Throwable error) {
        try {
            if (error == null) {
                VkLog.LOGGER.warn("[OpenAL] {}", message);
            } else {
                VkLog.LOGGER.warn("[OpenAL] {}", message, error);
            }
        } catch (Throwable t) {
            System.err.println("[FML-OpenAL] " + message + (error == null ? "" : ": " + error));
        }
    }
}
