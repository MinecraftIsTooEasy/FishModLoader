package net.xiaoyu233.fml.vulkan.render.legacy;

/** {@code fml.vulkan.legacyMirror} (1.16.3). Shared type of NR-08. */
public enum LegacyMirrorMode {
    LAZY, EAGER, OFF;

    public static LegacyMirrorMode parse(String s) {
        if (s != null) {
            for (LegacyMirrorMode m : values()) {
                if (m.name().equalsIgnoreCase(s.trim())) {
                    return m;
                }
            }
        }
        return LAZY;
    }
}
