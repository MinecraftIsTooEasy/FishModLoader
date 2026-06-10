package cpw.mods.fml.common;

import net.xiaoyu233.fml.FishModLoader;
import org.apache.logging.log4j.Level;

import java.util.logging.Logger;

/**
 * Stub for cpw.mods.fml.common.FMLLog (Forge 1.6.4).
 * Forge mods log via this static facade. We route everything to
 * FishModLoader's logger.
 */
public class FMLLog {

    private static final Logger JUL_LOGGER = Logger.getLogger("ForgeModLoader");

    private static org.apache.logging.log4j.Logger logger() {
        return FishModLoader.LOGGER;
    }

    public static void log(Level level, String format, Object... data) {
        logger().log(level, String.format(format, data));
    }

    public static void log(String channel, Level level, String format, Object... data) {
        logger().log(level, "[" + channel + "] " + String.format(format, data));
    }

    public static void log(Level level, Throwable ex, String format, Object... data) {
        logger().log(level, String.format(format, data), ex);
    }

    public static void log(String channel, Level level, Throwable ex, String format, Object... data) {
        logger().log(level, "[" + channel + "] " + String.format(format, data), ex);
    }

    public static void severe(String format, Object... data)  { log(Level.ERROR, format, data); }
    public static void warning(String format, Object... data) { log(Level.WARN, format, data); }
    public static void info(String format, Object... data)    { log(Level.INFO, format, data); }
    public static void fine(String format, Object... data)    { log(Level.DEBUG, format, data); }
    public static void finer(String format, Object... data)   { log(Level.TRACE, format, data); }
    public static void finest(String format, Object... data)  { log(Level.TRACE, format, data); }

    public static Logger getLogger() {
        return JUL_LOGGER;
    }

    public static void makeLog(String logChannel) {
        // no-op; all logs go to the shared FML logger
    }
}
