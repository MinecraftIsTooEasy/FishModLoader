package net.xiaoyu233.fml.vulkan.testbot;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The checks that replace the GL error polling of the phase-1 bot (ARCHITECTURE-NATIVE 7.2): validation message count, {@code GLAudit} game-site count, {@code GlShim}
 * fallback count, mixin apply audit and the {@code FrameStats} counters. Every counter owned by another package is read reflectively, so the bot runs before that
 * package lands and survives a signature change: an unavailable counter reads as -1 and is reported as "n/a", never as a failure.
 */
public final class NativeChecks {
    private static final AtomicLong VULKAN_MESSAGES = new AtomicLong();
    private static final AtomicLong VALIDATION_MESSAGES = new AtomicLong();

    private NativeChecks() {
    }

    /** One reading of all counters. -1 = not available in this tree. */
    public record Snapshot(long vulkanMessages, long validationMessages, long gameSites, long modSites, long shimFallbacks, long inlineCopies, long pipelinesCreatedRenderThread,
                           long pipelineMisses) {
        /** Counter-wise difference {@code this - earlier}; an unavailable counter stays -1. */
        public Snapshot minus(Snapshot e) {
            return new Snapshot(vulkanMessages - e.vulkanMessages, validationMessages - e.validationMessages, sub(gameSites, e.gameSites), sub(modSites, e.modSites),
                sub(shimFallbacks, e.shimFallbacks), sub(inlineCopies, e.inlineCopies), sub(pipelinesCreatedRenderThread, e.pipelinesCreatedRenderThread),
                sub(pipelineMisses, e.pipelineMisses));
        }

        private static long sub(long a, long b) {
            return a < 0 || b < 0 ? -1 : a - b;
        }

        public String text() {
            return "vulkan messages " + n(vulkanMessages) + ", validation messages " + n(validationMessages) + ", GLAudit game sites " + n(gameSites) + ", GLAudit mod sites "
                + n(modSites) + ", GlShim fallbacks " + n(shimFallbacks) + ", inline copies " + n(inlineCopies) + ", pipelines created on the render thread "
                + n(pipelinesCreatedRenderThread) + ", pipeline misses " + n(pipelineMisses);
        }

        private static String n(long v) {
            return v < 0 ? "n/a" : Long.toString(v);
        }
    }

    /** Called by {@link LogWatch} for every warn/error event of the backend logger whose text starts with {@code [Vulkan} (debug utils messenger, validation layer, MoltenVK). */
    static void onVulkanMessage(String text) {
        VULKAN_MESSAGES.incrementAndGet();
        if (text.contains("VUID-") || text.contains("Validation") || text.contains("validation")) {
            VALIDATION_MESSAGES.incrementAndGet();
        }
    }

    public static Snapshot snapshot() {
        long inline = -1;
        long created = -1;
        long misses = -1;
        try {
            Class<?> stats = Class.forName("net.xiaoyu233.fml.vulkan.render.frame.FrameStats");
            Object frame = stats.getMethod("latest").invoke(null);
            if (frame != null) {
                inline = recordLong(frame, "inlineCopies");
                created = recordLong(frame, "pipelinesCreatedRenderThread");
                misses = recordLong(frame, "pipelineMisses");
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // FrameStats not present or changed
        }
        return new Snapshot(VULKAN_MESSAGES.get(), VALIDATION_MESSAGES.get(), staticLong("net.xiaoyu233.fml.vulkan.render.legacy.GLAudit", "gameSiteCount"),
            staticLong("net.xiaoyu233.fml.vulkan.render.legacy.GLAudit", "modSiteCount"), staticLong("net.xiaoyu233.fml.vulkan.render.legacy.GLAudit", "shimFallbacks"), inline, created, misses);
    }

    private static long recordLong(Object record, String accessor) {
        try {
            Object v = record.getClass().getMethod(accessor).invoke(record);
            return v instanceof Number n ? n.longValue() : -1;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    private static long staticLong(String className, String method) {
        try {
            Object v = Class.forName(className).getMethod(method).invoke(null);
            return v instanceof Number n ? n.longValue() : -1;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return -1;
        }
    }

    /** True when the native renderer is on and no area is switched off: the gates of 7.1.5 apply. With areas off the safety net legitimately issues GL sites. */
    public static boolean gatesApply() {
        try {
            Class<?> cfg = Class.forName("net.xiaoyu233.fml.vulkan.VulkanConfig");
            boolean on = (Boolean) cfg.getMethod("nativeEnabled").invoke(null);
            String off = String.valueOf(System.getProperty("fml.vulkan.native.off", "")).trim();
            return on && off.isEmpty();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return false;
        }
    }

    /** Mixins of the enabled native areas that were not applied (area/Mixin); null when the plugin is not available. */
    @SuppressWarnings("unchecked")
    public static List<String> missingMixins() {
        try {
            Class<?> plugin = Class.forName("net.xiaoyu233.fml.vulkan.mixin.nat.NativeMixinPlugin");
            return (List<String>) plugin.getMethod("missing").invoke(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    /** Number of recorded mixin applications; -1 when the plugin is not available. */
    public static long appliedMixins() {
        return staticLong("net.xiaoyu233.fml.vulkan.mixin.nat.NativeMixinPlugin", "appliedCount");
    }

    /** Writes the mixin audit file ({@code <docs>/native/mixin-audit-<tag>.md}) when {@code fml.vulkan.native.audit=true}; no-op otherwise. */
    public static void writeMixinAudit(String tag) {
        try {
            Method m = Class.forName("net.xiaoyu233.fml.vulkan.mixin.nat.NativeMixinPlugin").getMethod("writeAuditIfEnabled", String.class);
            m.invoke(null, tag);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // plugin absent
        }
    }

    /**
     * Adds the counter delta of one step to its report section: validation messages and (when {@link #gatesApply()}) game sites and shim fallbacks are failures,
     * everything else is information.
     */
    static void report(Report.Section s, Snapshot before, Snapshot after) {
        Snapshot d = after.minus(before);
        s.info("native counters (delta of this step)", d.text());
        s.check(d.validationMessages() == 0, "validation messages 0", d.validationMessages() + " validation message(s) during this step");
        if (gatesApply()) {
            if (d.gameSites() >= 0) {
                s.check(d.gameSites() == 0, "GLAudit game sites 0", d.gameSites() + " GL game site(s) were hit with the native renderer on");
            }
            if (d.shimFallbacks() >= 0) {
                s.check(d.shimFallbacks() == 0, "GlShim fallbacks 0", d.shimFallbacks() + " GlShim.unhandled fallback(s)");
            }
        }
    }
}
