package net.xiaoyu233.fml.vulkan.opt;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.xiaoyu233.fml.FishModLoader;
import net.xiaoyu233.fml.vulkan.mixin.opt.OptMixinPlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Disables opt areas whose behaviour is already provided by a mod of the user's pack. Runs once, lazily (the first
 * {@link OptMixinPlugin#areaActive} call, i.e. when the opt mixin configs load, after mod discovery), then logs one WARN per
 * conflict and the effective flags at INFO.
 * <p>Conflict table (mod id -> areas): mithrilcore -> fps, quality; better_game_setting -> quality;
 * rusted_iron_core -> reload; shadersmodcore -> quality. Test: {@code -Dfml.vulkan.opt.fakeConflicts=quality}.
 */
public final class ConflictGuard {
    private static final Logger LOGGER = LogManager.getLogger("FML-Opt");
    private static final Object LOCK = new Object();
    private static volatile boolean done;
    private static boolean running;

    /** Mod id -> (area -> reason). Ids: mithrilcore, better_game_setting, rusted_iron_core (read from the jars), shadersmodcore. */
    private static final Map<String, Map<String, String>> TABLE = new LinkedHashMap<>();

    static {
        TABLE.put("mithrilcore", Map.of(
                "fps", "MithrilCore already implements a dynamic FPS limit",
                "quality", "MithrilCore already changes light/quality settings"));
        TABLE.put("better_game_setting", Map.of(
                "quality", "Better Game Setting owns the quality/options screen and frame cap"));
        TABLE.put("rusted_iron_core", Map.of(
                "reload", "RustedIronCore redirects texture loading in the resource reload"));
        TABLE.put("shadersmodcore", Map.of(
                "quality", "ShadersModCore overrides the same rendering classes"));
    }

    private ConflictGuard() {
    }

    /** Detects conflicts once and disables the affected areas. Idempotent, never throws, re-entrant safe. */
    public static void detect() {
        if (done) {
            return;
        }
        synchronized (LOCK) {
            if (done || running) {
                return;
            }
            running = true;
            try {
                run();
            } catch (Throwable t) {
                LOGGER.warn("ConflictGuard failed, no area was disabled: {}", t.toString());
            } finally {
                done = true;
                running = false;
            }
        }
        try {
            LOGGER.info("Opt flags: {}", OptFlags.dump());
        } catch (Throwable ignored) {
        }
    }

    private static void run() {
        Map<String, Set<String>> reasons = new LinkedHashMap<>(); // area -> reasons
        if (!OptFlags.bool("fml.vulkan.opt.ignoreConflicts", false)) {
            for (Map.Entry<String, Map<String, String>> e : TABLE.entrySet()) {
                if (hasMod(e.getKey())) {
                    for (Map.Entry<String, String> a : e.getValue().entrySet()) {
                        reasons.computeIfAbsent(a.getKey(), k -> new LinkedHashSet<>()).add("mod '" + e.getKey() + "': " + a.getValue());
                    }
                }
            }
        }
        String fake = System.getProperty("fml.vulkan.opt.fakeConflicts");
        if (fake != null) {
            for (String area : fake.split(",")) {
                area = area.trim();
                if (!area.isEmpty()) {
                    reasons.computeIfAbsent(area, k -> new LinkedHashSet<>()).add("fake conflict (fml.vulkan.opt.fakeConflicts)");
                }
            }
        }
        for (Map.Entry<String, Set<String>> e : reasons.entrySet()) {
            OptMixinPlugin.disable(e.getKey());
            LOGGER.warn("Opt area '{}' disabled: {}", e.getKey(), String.join("; ", e.getValue()));
        }
    }

    private static boolean hasMod(String id) {
        try {
            return FishModLoader.hasMod(id);
        } catch (Throwable t) {
            return false;
        }
    }
}
