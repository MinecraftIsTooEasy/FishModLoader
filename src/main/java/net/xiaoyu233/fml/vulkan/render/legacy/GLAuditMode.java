package net.xiaoyu233.fml.vulkan.render.legacy;

/** {@code fml.vulkan.glAudit} (1.16.5): OFF = counters only, FIRST = stack walk for the first 64 calls per function and every 4096th, FULL = every call. */
public enum GLAuditMode {
    OFF, FIRST, FULL;

    public static GLAuditMode parse(String s) {
        if (s != null) {
            for (GLAuditMode m : values()) {
                if (m.name().equalsIgnoreCase(s.trim())) {
                    return m;
                }
            }
        }
        return FIRST;
    }
}
