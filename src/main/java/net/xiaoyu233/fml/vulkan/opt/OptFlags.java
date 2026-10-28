package net.xiaoyu233.fml.vulkan.opt;

import java.util.LinkedHashMap;
import java.util.Map;
import net.xiaoyu233.fml.vulkan.mixin.opt.OptMixinPlugin;

/**
 * Typed accessors for the {@code fml.vulkan.opt.*} system properties (also settable as {@code opt.*} keys in
 * {@code config/fml-vulkan.properties}).
 * <pre>
 * Property                              Default  Meaning
 * fml.vulkan.opt                        true     master switch; false disables every area
 * fml.vulkan.opt.net                    true     area: network (R1b)
 * fml.vulkan.opt.regionio               true     area: region/chunk IO
 * fml.vulkan.opt.entity                 true     area: entity tracking/update
 * fml.vulkan.opt.input                  true     area: input handling
 * fml.vulkan.opt.fps                    true     area: FPS policy (menu / unfocused / AFK)
 * fml.vulkan.opt.quality                true     area: quality switches (shadows, clouds, ...)
 * fml.vulkan.opt.reload                 true     area: resource reload
 * fml.vulkan.opt.&lt;area&gt;.&lt;feature&gt;     true     one feature of an area (only effective while the area is on)
 * fml.vulkan.opt.workers                auto     OptWorkers thread count (default max(1, cores/4))
 * fml.vulkan.opt.fakeConflicts          (none)   test: comma separated areas ConflictGuard disables as if a mod conflicted
 * fml.vulkan.opt.ignoreConflicts        false    true: do not disable areas for conflicting mods
 * </pre>
 * Values: {@code false/0/off/no} are off, anything else non-empty is on; an unset property returns the caller's default.
 */
public final class OptFlags {
    public static final String PREFIX = "fml.vulkan.opt";

    /** Keys shown in the startup dump (area switches first); extra keys that are set are appended. */
    private static final String[] KNOWN = {"", "net", "regionio", "entity", "input", "fps", "quality", "reload",
            "workers", "fakeConflicts", "ignoreConflicts"};

    private OptFlags() {
    }

    public static String key(String area, String feature) {
        StringBuilder sb = new StringBuilder(PREFIX);
        if (area != null && !area.isEmpty()) {
            sb.append('.').append(area);
            if (feature != null && !feature.isEmpty()) {
                sb.append('.').append(feature);
            }
        }
        return sb.toString();
    }

    /** True when the area is active (conflicts, master switch and area switch considered). */
    public static boolean area(String area) {
        return OptMixinPlugin.areaActive(area);
    }

    /** True when the area is active and the feature is not switched off; {@code def} applies when the feature property is unset. */
    public static boolean feature(String area, String feature, boolean def) {
        return area(area) && bool(key(area, feature), def);
    }

    public static boolean bool(String property, boolean def) {
        String v = System.getProperty(property);
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        v = v.trim();
        return !(v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("off") || v.equalsIgnoreCase("no"));
    }

    public static int integer(String property, int def) {
        String v = System.getProperty(property);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static long longValue(String property, long def) {
        String v = System.getProperty(property);
        if (v == null) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static double decimal(String property, double def) {
        String v = System.getProperty(property);
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static String string(String property, String def) {
        String v = System.getProperty(property);
        return v == null || v.isEmpty() ? def : v;
    }

    /** The effective flags (area switches resolved through conflicts, then every other opt.* property that is set). */
    public static Map<String, String> effective() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(PREFIX, String.valueOf(bool(PREFIX, true)));
        for (String a : OptMixinPlugin.AREAS) {
            m.put(key(a, null), String.valueOf(OptMixinPlugin.areaActive(a)));
        }
        for (String name : new java.util.TreeSet<>(System.getProperties().stringPropertyNames())) {
            if (name.startsWith(PREFIX + ".") && !m.containsKey(name)) {
                m.put(name, System.getProperty(name));
            }
        }
        return m;
    }

    /** One-line dump of {@link #effective()}. */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : effective().entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey().substring(Math.min(e.getKey().length(), "fml.vulkan.".length()))).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
