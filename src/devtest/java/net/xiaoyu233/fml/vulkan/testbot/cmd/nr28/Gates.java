package net.xiaoyu233.fml.vulkan.testbot.cmd.nr28;

import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.mixin.nat.NativeMixinPlugin;
import net.xiaoyu233.fml.vulkan.render.frame.FrameStats;
import net.xiaoyu233.fml.vulkan.render.gpu.PipelineLibrary;
import net.xiaoyu233.fml.vulkan.testbot.NativeChecks;
import net.xiaoyu233.fml.vulkan.testbot.Report;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Shared signatures of the NR-28 integration gates (section 7.1 / 7.2); each gate reports into its own section. Bodies are filled by the subtasks. */
final class Gates {
    /** Number of native mixin configs the audit expects (ARCHITECTURE-NATIVE 7.1.3). */
    private static final int EXPECTED_NATIVE_CONFIGS = 22;
    /** Default minimum number of frames the route must have recorded (args: minFrames=N overrides it). */
    private static final long DEFAULT_MIN_FRAMES = 600L;
    private static final Pattern CLIENT_LIST = Pattern.compile("\"client\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");
    /** Mixin warnings that mean a native rewrite did not apply as written (ARCHITECTURE-NATIVE 7.1.3). */
    private static final Pattern MIXIN_WARNINGS = Pattern.compile("cannot reduce visibility|invalid descriptor|redirect conflict|Mixin apply failed"
        + "|Critical injection failure", Pattern.CASE_INSENSITIVE);

    private Gates() {
    }

    /**
     * Mixin apply audit (7.1.3): with the native renderer on, the plugin must be present, every mixin of the enabled native configs applied,
     * the 22 configs readable, the applied count above zero, and the game log free of Mixin warnings of the 7.1.3 kinds.
     */
    static void mixinAudit(Report.Section s, String[] args) throws Exception {
        boolean nativeOn = VulkanConfig.nativeEnabled();
        List<String> missing = NativeChecks.missingMixins();
        if (missing == null) {
            if (nativeOn) {
                s.fail("mixinAudit", "NativeMixinPlugin not available although fml.vulkan.native=true");
            } else {
                s.info("mixinAudit", "native renderer off (fml.vulkan.native=false); native mixin audit not applicable");
            }
            return;
        }
        NativeChecks.writeMixinAudit("gate");
        long applied = NativeChecks.appliedMixins();
        s.info("applied mixins", Long.toString(applied));
        s.check(applied > 0, "native mixins recorded as applied", "appliedMixins = " + applied);
        s.check(missing.isEmpty(), "all native mixins applied", missing.size() + " missing: " + String.join(", ", missing));

        // the configs: all 22 registered and readable; the enabled areas list at least one mixin
        List<String> configs = NativeMixinPlugin.registeredConfigs();
        s.check(configs.size() == EXPECTED_NATIVE_CONFIGS, EXPECTED_NATIVE_CONFIGS + " native configs registered", "registered " + configs.size());
        int configured = 0;
        for (String cfg : configs) {
            String area = cfg.replace("fishmodloader.native.", "").replace(".mixins.json", "");
            List<String> listed = listedClientMixins(cfg);
            if (listed == null) {
                s.fail("config " + area, "cannot read " + cfg + " from the class path");
                continue;
            }
            if (VulkanConfig.areaActive(area)) {
                configured += listed.size();
            }
        }
        s.info("configured mixins of enabled areas", Integer.toString(configured));
        s.check(configured > 0, "configured native mixins of enabled areas > 0", "found " + configured);

        // Mixin warnings in the game log of this run
        Path log = VulkanConfig.gameDir().resolve("logs").resolve("latest.log");
        if (!Files.isRegularFile(log)) {
            s.fail("mixin warnings", "no game log to scan at " + log);
            return;
        }
        List<String> hits = new ArrayList<>();
        try (Stream<String> lines = Files.lines(log, StandardCharsets.UTF_8)) {
            lines.filter(l -> MIXIN_WARNINGS.matcher(l).find()).forEach(l -> {
                if (hits.size() < 5) {
                    hits.add(l.length() > 200 ? l.substring(0, 200) : l);
                }
            });
        } catch (IOException e) {
            s.fail("mixin warnings", "cannot read " + log + ": " + e);
            return;
        }
        s.check(hits.isEmpty(), "no Mixin warnings in latest.log", "first hits: " + String.join(" | ", hits));
    }

    /** The {@code client} mixin names of a native config resource; null when the resource cannot be read. */
    private static List<String> listedClientMixins(String cfg) throws IOException {
        try (InputStream in = Gates.class.getClassLoader().getResourceAsStream(cfg)) {
            if (in == null) {
                return null;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            Matcher m = CLIENT_LIST.matcher(json);
            if (m.find()) {
                Matcher q = QUOTED.matcher(m.group(1));
                while (q.find()) {
                    out.add(q.group(1));
                }
            }
            return out;
        }
    }

    /**
     * Runtime counters gate over the route: GLAudit game sites 0, shim fallbacks 0, validation 0, and inline copies 0 in every retained frame.
     * Fails when the counters are unavailable or the route recorded fewer than minFrames frames.
     */
    static void counterGate(Report.Section s, String[] args) throws Exception {
        NativeChecks.Snapshot n = NativeChecks.snapshot();
        s.info("counters", n.text());
        if (!NativeChecks.gatesApply()) {
            s.info("counterGate", "gates do not apply (native off or areas switched off); counters informational only");
            s.check(n.validationMessages() == 0, "validation messages 0", n.validationMessages() + " validation message(s)");
            return;
        }
        Long minFrames = minFrames(args);
        if (minFrames == null) {
            s.fail("counterGate", "bad argument, expected minFrames=<number>");
            return;
        }
        s.check(n.gameSites() >= 0, "GLAudit game sites observed", "game sites = " + n.gameSites() + " (unavailable)");
        s.check(n.shimFallbacks() >= 0, "GlShim fallbacks observed", "shim fallbacks = " + n.shimFallbacks() + " (unavailable)");
        s.check(n.gameSites() == 0, "GLAudit game sites 0", "game sites = " + n.gameSites());
        s.check(n.shimFallbacks() == 0, "GlShim fallbacks 0", "shim fallbacks = " + n.shimFallbacks());
        s.check(n.validationMessages() == 0, "validation messages 0", n.validationMessages() + " validation message(s)");

        long frames = FrameStats.frameCount();
        s.check(frames >= minFrames, "route recorded >= " + minFrames + " frames", "frames recorded = " + frames);
        long[] w = inlineCopiesWindow();
        s.check(w[2] > 0, "inline copy counter observed", "no retained frames");
        s.check(w[0] == 0, "inline copies 0 outside title", "inlineCopies sum " + w[0] + ", max per frame " + w[1] + " over " + w[2] + " retained frame(s)");
    }

    /** Scans the retained FrameStats ring (up to {@link FrameStats#RING} frames, not just the latest): {sum, max per frame, frames scanned}. */
    private static long[] inlineCopiesWindow() {
        long total = 0;
        long max = 0;
        long frames = 0;
        for (int i = 0; ; i++) {
            FrameStats.Frame f = FrameStats.ago(i);
            if (f == null) {
                break;
            }
            total += f.inlineCopies();
            max = Math.max(max, f.inlineCopies());
            frames++;
        }
        return new long[] {total, max, frames};
    }

    /**
     * Pipeline warm-up gate (second run): render-thread creations 0, fallback misses 0 and skipped misses 0. The PipelineLibrary counters are
     * cumulative since process start, so zero over the whole run implies zero after the title screen. Fails when the library is not running,
     * or when the route recorded fewer than minFrames frames.
     */
    static void pipelineGate(Report.Section s, String[] args) throws Exception {
        if (!NativeChecks.gatesApply()) {
            s.info("pipelineGate", "gates do not apply (native off or areas switched off); pipeline counters not gated");
            return;
        }
        Long minFrames = minFrames(args);
        if (minFrames == null) {
            s.fail("pipelineGate", "bad argument, expected minFrames=<number>");
            return;
        }
        s.check(PipelineLibrary.isInitialized(), "pipeline library running", "PipelineLibrary is not initialized; counters cannot be read");
        long created = PipelineLibrary.renderThreadCreations();
        long fallback = PipelineLibrary.fallbackDraws();
        long skipped = PipelineLibrary.skippedDraws();
        long frames = FrameStats.frameCount();
        s.info("pipeline counters (cumulative since start)", "pipelinesCreatedRenderThread " + created + ", pipelineMissesFallback " + fallback
            + ", pipelineMissesSkipped " + skipped + ", frames recorded " + frames);
        s.check(frames >= minFrames, "route recorded >= " + minFrames + " frames", "frames recorded = " + frames);
        s.check(created == 0, "pipelinesCreatedRenderThread 0", "created on render thread = " + created);
        s.check(fallback == 0, "pipelineMissesFallback 0", "fallback misses = " + fallback);
        s.check(skipped == 0, "pipelineMissesSkipped 0", "skipped misses = " + skipped);
    }

    /** The optional {@code minFrames=N} argument; DEFAULT_MIN_FRAMES when absent, null when malformed. */
    private static Long minFrames(String[] args) {
        for (String a : args) {
            if (a.startsWith("minFrames=")) {
                try {
                    long v = Long.parseLong(a.substring("minFrames=".length()).trim());
                    return v >= 0 ? v : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return DEFAULT_MIN_FRAMES;
    }

    /** Seed list refresh: merges the run journal into native-pipelines.seed (sorted, unique) at the path given in args[0]. */
    static void seedRefresh(Report.Section s, String[] args) throws Exception {
        SeedTools.refresh(s, args);
    }

    /** Parses ../docs/native/gl-sites-transformed.json and checks it is 0 (args: json path). */
    static void scanGate(Report.Section s, String[] args) throws Exception {
        ScanTools.gate(s, args);
    }
}
