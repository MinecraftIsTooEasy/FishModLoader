package net.xiaoyu233.fml.reload.utils;

public final class NetClientHandlerHelper {
    private static byte connectionCompatibilityLevel;

    private NetClientHandlerHelper() {}

    public static void setConnectionCompatibilityLevel(byte level) {
        connectionCompatibilityLevel = level;
    }

    public static byte getConnectionCompatibilityLevel() {
        return connectionCompatibilityLevel;
    }
}
