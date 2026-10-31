package org.lwjgl;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * LWJGL 2 API: utilities (public API and semantics of LWJGL 2.9.0, as far as they make sense without LWJGL 2 natives).
 * Owned by module E.
 */
public class LWJGLUtil {
    public static final int PLATFORM_LINUX = 1;
    public static final int PLATFORM_MACOSX = 2;
    public static final int PLATFORM_WINDOWS = 3;

    public static final String PLATFORM_LINUX_NAME = "linux";
    public static final String PLATFORM_MACOSX_NAME = "macosx";
    public static final String PLATFORM_WINDOWS_NAME = "windows";

    /** 16x16 and 32x32 RGBA window icons (a plain blue square, the LWJGL logo is not shipped), never null. */
    public static final ByteBuffer LWJGLIcon16x16 = createIcon(16);
    public static final ByteBuffer LWJGLIcon32x32 = createIcon(32);

    /** True with -Dorg.lwjgl.util.Debug=true. */
    public static final boolean DEBUG = getPrivilegedBoolean("org.lwjgl.util.Debug");
    /** True unless -Dorg.lwjgl.util.NoChecks=true. */
    public static final boolean CHECKS = !getPrivilegedBoolean("org.lwjgl.util.NoChecks");

    private static final int PLATFORM;

    static {
        String osName = System.getProperty("os.name", "");
        if (osName.startsWith("Windows")) {
            PLATFORM = PLATFORM_WINDOWS;
        } else if (osName.startsWith("Linux") || osName.startsWith("FreeBSD") || osName.startsWith("SunOS") || osName.startsWith("Unix")) {
            PLATFORM = PLATFORM_LINUX;
        } else if (osName.startsWith("Mac OS X") || osName.startsWith("Darwin")) {
            PLATFORM = PLATFORM_MACOSX;
        } else {
            throw new LinkageError("Unknown platform: " + osName);
        }
    }

    public LWJGLUtil() {
    }

    private static ByteBuffer createIcon(int size) {
        ByteBuffer icon = ByteBuffer.allocateDirect(size * size * 4);
        for (int i = 0; i < size * size; i++) {
            icon.put((byte) 0x3A).put((byte) 0x7D).put((byte) 0xB5).put((byte) 0xFF);
        }
        icon.flip();
        return icon;
    }

    public static int getPlatform() {
        return PLATFORM;
    }

    public static String getPlatformName() {
        return switch (PLATFORM) {
            case PLATFORM_LINUX -> PLATFORM_LINUX_NAME;
            case PLATFORM_WINDOWS -> PLATFORM_WINDOWS_NAME;
            case PLATFORM_MACOSX -> PLATFORM_MACOSX_NAME;
            default -> "unknown";
        };
    }

    /** The paths a native library would be searched in. The LWJGL 3 natives are extracted by FML, this only lists the names. */
    public static String[] getLibraryPaths(String libname, String platformLibname, ClassLoader classloader) {
        return getLibraryPaths(libname, new String[]{platformLibname}, classloader);
    }

    public static String[] getLibraryPaths(String libname, String[] platformLibnames, ClassLoader classloader) {
        String[] names = new String[platformLibnames.length + 1];
        names[0] = libname;
        System.arraycopy(platformLibnames, 0, names, 1, platformLibnames.length);
        return names;
    }

    public static boolean getPrivilegedBoolean(String propertyName) {
        return Boolean.getBoolean(propertyName);
    }

    public static Integer getPrivilegedInteger(String propertyName) {
        return Integer.getInteger(propertyName);
    }

    public static Integer getPrivilegedInteger(String propertyName, int defaultValue) {
        return Integer.getInteger(propertyName, defaultValue);
    }

    /** Debug output: only with -Dorg.lwjgl.util.Debug=true. */
    public static void log(CharSequence msg) {
        if (DEBUG) {
            System.err.println("[LWJGL] " + msg);
        }
    }

    public static boolean isMacOSXEqualsOrBetterThan(int majorVersion, int minorVersion) {
        String osVersion = System.getProperty("os.version");
        StringTokenizer versionTokenizer = new StringTokenizer(osVersion, ".");
        int major;
        int minor;
        try {
            String majorToken = versionTokenizer.nextToken();
            String minorToken = versionTokenizer.nextToken();
            major = Integer.parseInt(majorToken);
            minor = Integer.parseInt(minorToken);
        } catch (Exception e) {
            log("Exception occurred while trying to determine OS version: " + e);
            return false;
        }
        return major > majorVersion || (major == majorVersion && minor >= minorVersion);
    }

    /** Maps the values of the public static final int fields of the classes to their names (a duplicated value gets its hex string). */
    public static Map<Integer, String> getClassTokens(TokenFilter filter, Map<Integer, String> target, Class... tokenClasses) {
        return getClassTokens(filter, target, Arrays.asList(tokenClasses));
    }

    public static Map<Integer, String> getClassTokens(TokenFilter filter, Map<Integer, String> target, Iterable<Class> tokenClasses) {
        if (target == null) {
            target = new HashMap<>();
        }
        final int tokenModifiers = Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL;
        for (Class tokenClass : tokenClasses) {
            for (Field field : tokenClass.getDeclaredFields()) {
                if ((field.getModifiers() & tokenModifiers) == tokenModifiers && field.getType() == int.class) {
                    try {
                        final int value = field.getInt(null);
                        if (filter != null && !filter.accept(field, value)) {
                            continue;
                        }
                        if (target.containsKey(value)) {
                            target.put(value, toHexString(value));
                        } else {
                            target.put(value, field.getName());
                        }
                    } catch (IllegalAccessException e) {
                        // ignore
                    }
                }
            }
        }
        return target;
    }

    public static String toHexString(int value) {
        return "0x" + Integer.toHexString(value).toUpperCase();
    }

    /** Selects the fields {@link #getClassTokens} reports. */
    public interface TokenFilter {
        boolean accept(Field field, int value);
    }
}
