package net.xiaoyu233.fml.vulkan;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backend options. Every value is read from a system property on each call (module G pre-populates the properties from
 * {@code config/fml-vulkan.properties} for keys that are not set already), so callers that need a value per frame should
 * cache it. A blank or unparsable property counts as "not set" (unparsable values are logged once).
 */
public final class VulkanConfig {
    public static final String PROP_VSYNC = "fml.vulkan.vsync";
    public static final String PROP_PRESENT_MODE = "fml.vulkan.presentMode";
    public static final String PROP_FRAMES_IN_FLIGHT = "fml.vulkan.framesInFlight";
    public static final String PROP_RETINA = "fml.vulkan.retina";
    public static final String PROP_VALIDATION = "fml.vulkan.validation";
    public static final String PROP_DEBUG = "fml.vulkan.debug";
    public static final String PROP_CAPTURE = "fml.vulkan.capture";
    public static final String PROP_CAPTURE_DIR = "fml.vulkan.captureDir";
    public static final String PROP_EXIT_AFTER_FRAMES = "fml.vulkan.exitAfterFrames";
    public static final String PROP_RAW_MOUSE = "fml.vulkan.rawMouse";
    public static final String PROP_BORDERLESS = "fml.vulkan.borderless";
    public static final String PROP_NO_DRAW_MERGE = "fml.vulkan.noDrawMerge";
    public static final String PROP_GAME_DIR = "fml.gameDir";
    public static final String PROP_NATIVE = "fml.vulkan.native";
    public static final String PROP_NATIVE_OFF = "fml.vulkan.native.off";
    public static final String PROP_NATIVE_AUDIT = "fml.vulkan.native.audit";
    public static final String PROP_GL_AUDIT = "fml.vulkan.glAudit";
    public static final String PROP_LEGACY_MIRROR = "fml.vulkan.legacyMirror";
    public static final String PROP_FORCE_LOAD_GAME_CLASSES = "fml.vulkan.forceLoadGameClasses";
    public static final String PROP_MESH_THREADS = "fml.vulkan.meshThreads";
    public static final String PROP_SECTION_COMMIT_MS = "fml.vulkan.sectionCommitMs";
    public static final String PROP_SECTION_UPLOAD_MIB = "fml.vulkan.sectionUploadMiB";
    public static final String PROP_CAPTURE_MS = "fml.vulkan.captureMs";
    public static final String PROP_CAPTURES_PER_FRAME = "fml.vulkan.capturesPerFrame";
    public static final String PROP_TEXTURE_VERSIONS = "fml.vulkan.textureVersions";
    public static final String PROP_PIPELINE_ASYNC = "fml.vulkan.pipelineAsync";
    public static final String PROP_FROZEN = "fml.vulkan.frozen";
    public static final String PROP_SHADOW_CHECK = "fml.vulkan.shadowCheck";
    public static final String PROP_CAVE_CULLING = "fml.vulkan.caveCulling";
    public static final String PROP_MDI = "fml.vulkan.mdi";
    public static final String PROP_TRANSLUCENCY = "fml.vulkan.translucency";
    public static final String PROP_MESH_DENY_CLASSES = "fml.vulkan.meshDenyClasses";
    public static final String PROP_MIPMAPS = "fml.vulkan.mipmaps";
    public static final String PROP_ANISOTROPY = "fml.vulkan.anisotropy";
    public static final String PROP_PARTICLES_FAST = "fml.vulkan.particles.fast";
    public static final String PROP_PARTICLES_SORT = "fml.vulkan.particles.sort";
    public static final String PROP_LATENCY = "fml.vulkan.latency";
    public static final String PROP_CAP_UNFOCUSED = "fml.vulkan.capUnfocused";
    public static final String PROP_CAP_MINIMISED = "fml.vulkan.capMinimised";
    public static final String PROP_DOWNTIME_AFTER_PRESENT = "fml.vulkan.downtimeAfterPresent";
    public static final String PROP_INPUT_GUI_PER_FRAME = "fml.vulkan.input.guiPerFrame";
    public static final String PROP_INPUT_RESTORE_HELD_KEYS = "fml.vulkan.input.restoreHeldKeys";
    public static final String PROP_GPU_TIMERS = "fml.vulkan.gpuTimers";
    public static final String PROP_PRESENT_MIN_MS = "fml.vulkan.presentMinMs";
    public static final String PROP_DEBUG_OVERLAY = "fml.vulkan.debugOverlay";
    public static final String PROP_GPU = "fml.vulkan.gpu";
    public static final String PROP_MAC_PRESENTER = "fml.vulkan.macPresenter";
    public static final String PROP_PRESENTER_SURFACES = "fml.vulkan.presenterSurfaces";

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private VulkanConfig() {
    }

    /**
     * fml.vulkan.vsync. The effective vsync normally follows {@code Display.setVSyncEnabled}; this value is only the initial
     * state (true when unset) and the override when {@link #vsyncForced()} is true.
     */
    public static boolean vsync() {
        return bool(PROP_VSYNC, true);
    }

    /** True when fml.vulkan.vsync is set explicitly, i.e. it overrides {@code Display.setVSyncEnabled}. */
    public static boolean vsyncForced() {
        return raw(PROP_VSYNC) != null;
    }

    /** fml.vulkan.presentMode: one of auto|fifo|mailbox|immediate (lower case, default auto: vsync -> FIFO, else IMMEDIATE -> MAILBOX -> FIFO). */
    public static String presentMode() {
        String v = raw(PROP_PRESENT_MODE);
        if (v == null) return "auto";
        String mode = v.toLowerCase(Locale.ROOT);
        switch (mode) {
            case "auto", "fifo", "mailbox", "immediate" -> {
                return mode;
            }
            default -> {
                warnInvalid(PROP_PRESENT_MODE, v, "auto");
                return "auto";
            }
        }
    }

    /** fml.vulkan.framesInFlight: default 2, clamped to 1..3 (the macOS layer presenter uses 3 unless this is set, see RenderCore). */
    public static int framesInFlight() {
        return Math.max(1, Math.min(3, integer(PROP_FRAMES_IN_FLIGHT, 2)));
    }

    /** fml.vulkan.debugOverlay: the Vulkan information lines of the F3 screen, default true. */
    public static boolean debugOverlay() {
        return bool(PROP_DEBUG_OVERLAY, true);
    }

    /** True when fml.vulkan.framesInFlight is set explicitly (system property or config file). */
    public static boolean framesInFlightSet() {
        String v = raw(PROP_FRAMES_IN_FLIGHT);
        return v != null && !v.isBlank();
    }

    /**
     * fml.vulkan.gpu: force a physical device, by index in vkEnumeratePhysicalDevices order ("0", "1") or by a case-insensitive part of its
     * name ("nvidia", "intel"); empty (default) = best score (discrete GPU before integrated, then the larger device-local heap).
     */
    public static String gpu() {
        String v = raw(PROP_GPU);
        return v == null ? "" : v.trim();
    }

    /** fml.vulkan.retina: macOS full-resolution framebuffer, default true. */
    public static boolean retina() {
        return bool(PROP_RETINA, true);
    }

    /** fml.vulkan.validation: enable VK_LAYER_KHRONOS_validation when present, default false. */
    public static boolean validation() {
        return bool(PROP_VALIDATION, false);
    }

    /** fml.vulkan.debug: extra logging and debug utils labels, default false. */
    public static boolean debug() {
        return bool(PROP_DEBUG, false);
    }

    /** fml.vulkan.capture, e.g. "60,300": presented-frame numbers whose offscreen color is written as PNG (sorted, distinct; empty when unset). */
    public static int[] captureFrames() {
        String v = raw(PROP_CAPTURE);
        if (v == null) return new int[0];
        int[] frames = new int[0];
        for (String token : v.split("[,;\\s]+")) {
            if (token.isEmpty()) continue;
            try {
                int frame = Integer.parseInt(token);
                if (frame < 0) throw new NumberFormatException(token);
                frames = Arrays.copyOf(frames, frames.length + 1);
                frames[frames.length - 1] = frame;
            } catch (NumberFormatException e) {
                warnInvalid(PROP_CAPTURE, token, "(ignored)");
            }
        }
        return Arrays.stream(frames).sorted().distinct().toArray();
    }

    /** fml.vulkan.captureDir, default {@code <gameDir>/vk-captures}; a relative value is resolved against {@link #gameDir()}. */
    public static Path captureDir() {
        String v = raw(PROP_CAPTURE_DIR);
        Path gameDir = gameDir();
        return v == null ? gameDir.resolve("vk-captures") : gameDir.resolve(v).normalize();
    }

    /** fml.vulkan.exitAfterFrames: the test harness quits the game after this many presented frames; 0 (default) = never. */
    public static long exitAfterFrames() {
        return Math.max(0L, longValue(PROP_EXIT_AFTER_FRAMES, 0L));
    }

    /** fml.vulkan.rawMouse: use GLFW raw mouse motion while the cursor is grabbed, default true. */
    public static boolean rawMouse() {
        return bool(PROP_RAW_MOUSE, true);
    }

    /**
     * fml.vulkan.borderless: fullscreen as a borderless window covering the monitor (otherwise exclusive), default true.
     * <b>macOS:</b> a plain borderless window cannot cover the menu bar and the Dock, so there the default is the window attached to
     * the monitor (GLFW "exclusive" path; hides both, keeps the desktop resolution when the requested size equals it) and the
     * borderless window is only used when this property is set explicitly (module E tests {@code System.getProperty} for presence,
     * so nothing may pre-populate it with the default value).
     */
    public static boolean borderlessFullscreen() {
        return bool(PROP_BORDERLESS, true);
    }

    /** fml.vulkan.noDrawMerge: record every draw immediately instead of merging contiguous draws (debugging aid), default false. */
    public static boolean noDrawMerge() {
        return bool(PROP_NO_DRAW_MERGE, false);
    }

    // ---------------------------------------------------------------------------------------------------------
    // native renderer (ARCHITECTURE-NATIVE 5.2); frozen after NR-00
    // ---------------------------------------------------------------------------------------------------------

    /** fml.vulkan.native: master switch of the native renderer (mixins, {@code render.*}), default true. */
    public static boolean nativeEnabled() {
        return bool(PROP_NATIVE, true);
    }

    /**
     * fml.vulkan.native.off: comma separated list of native mixin areas that stay on the phase-1 safety-net rendering (default empty).
     * True when {@code area} is listed. The infrastructure areas (core, minecraft, window, world, terrain, mesh, texture) can only be
     * disabled all together with {@code fml.vulkan.native=false}; the plugin ignores them here.
     */
    public static boolean nativeOff(String area) {
        String v = raw(PROP_NATIVE_OFF);
        if (v == null || area == null) return false;
        for (String token : v.split("[,;\\s]+")) {
            if (token.equalsIgnoreCase(area)) return true;
        }
        return false;
    }

    /** Areas that cannot be switched off individually (1.17, 5.12). */
    private static final java.util.Set<String> INFRASTRUCTURE_AREAS = java.util.Set.of("core", "minecraft", "window", "world", "terrain", "mesh", "texture");

    /** True when the native mixins/code of {@code area} are active: master switch on and (infrastructure area or not listed in native.off). */
    public static boolean areaActive(String area) {
        return nativeEnabled() && (INFRASTRUCTURE_AREAS.contains(area) || !nativeOff(area));
    }

    /** fml.vulkan.native.audit: write the mixin apply audit at the first world frame, default false. */
    public static boolean nativeAudit() {
        return bool(PROP_NATIVE_AUDIT, false);
    }

    /** fml.vulkan.glAudit: off | first | full, default first. */
    public static String glAudit() {
        return choice(PROP_GL_AUDIT, "first", "off", "first", "full");
    }

    /** fml.vulkan.legacyMirror: lazy | eager | off, default lazy. */
    public static String legacyMirror() {
        return choice(PROP_LEGACY_MIRROR, "lazy", "lazy", "eager", "off");
    }

    /** fml.vulkan.forceLoadGameClasses: load every inventory class after the world is entered (transformed bytecode scan), default false. */
    public static boolean forceLoadGameClasses() {
        return bool(PROP_FORCE_LOAD_GAME_CLASSES, false);
    }

    /** fml.vulkan.meshThreads: mesh worker threads, default clamp(cores - 2, 1, 8), hard cap 8. */
    public static int meshThreads() {
        int def = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 2));
        return Math.max(1, Math.min(8, integer(PROP_MESH_THREADS, def)));
    }

    /** fml.vulkan.sectionCommitMs: render-thread budget per frame for committing finished sections, default 2.0. */
    public static double sectionCommitMs() {
        return Math.max(0.0, decimal(PROP_SECTION_COMMIT_MS, 2.0));
    }

    /** fml.vulkan.sectionUploadMiB: section upload budget per frame in MiB, default 32. */
    public static int sectionUploadMiB() {
        return Math.max(1, integer(PROP_SECTION_UPLOAD_MIB, 32));
    }

    /** fml.vulkan.captureMs: render-thread budget per frame for far-set snapshot captures, default 1.5. */
    public static double captureMs() {
        return Math.max(0.0, decimal(PROP_CAPTURE_MS, 1.5));
    }

    /** fml.vulkan.capturesPerFrame: far-set snapshot captures per frame, default 64. */
    public static int capturesPerFrame() {
        return Math.max(1, integer(PROP_CAPTURES_PER_FRAME, 64));
    }

    /** fml.vulkan.textureVersions: versioned dynamic textures (1.11), default true. */
    public static boolean textureVersions() {
        return bool(PROP_TEXTURE_VERSIONS, true);
    }

    /** fml.vulkan.pipelineAsync: create pipelines on missing keys asynchronously (1.9), default true. */
    public static boolean pipelineAsync() {
        return bool(PROP_PIPELINE_ASYNC, true);
    }

    /** fml.vulkan.frozen: frozen-world capture mode (7.2), default false. */
    public static boolean frozen() {
        return bool(PROP_FROZEN, false);
    }

    /** fml.vulkan.shadowCheck: compare the native frame state with the safety-net values (7.2), default false. */
    public static boolean shadowCheck() {
        return bool(PROP_SHADOW_CHECK, false);
    }

    /** fml.vulkan.caveCulling: visibility BFS through section visibility graphs (2.5), default true. */
    public static boolean caveCulling() {
        return bool(PROP_CAVE_CULLING, true);
    }

    /** fml.vulkan.mdi: auto | on | off (multi-draw-indirect for terrain), default auto. */
    public static String mdi() {
        return choice(PROP_MDI, "auto", "auto", "on", "off");
    }

    /** fml.vulkan.translucency: sorted | prepass (2.7), default sorted. */
    public static String translucency() {
        return choice(PROP_TRANSLUCENCY, "sorted", "sorted", "prepass");
    }

    /** fml.vulkan.meshDenyClasses: comma separated block class names that always mesh on the render thread (2.3), default empty. */
    public static String meshDenyClasses() {
        String v = raw(PROP_MESH_DENY_CLASSES);
        return v == null ? "" : v;
    }

    /** fml.vulkan.mipmaps: mip levels of the terrain atlas, 0 = off (default). */
    public static int mipmaps() {
        return Math.max(0, Math.min(4, integer(PROP_MIPMAPS, 0)));
    }

    /** fml.vulkan.anisotropy: sampler anisotropy of the atlases, default 1 (off). */
    public static int anisotropy() {
        return Math.max(1, Math.min(16, integer(PROP_ANISOTROPY, 1)));
    }

    /** fml.vulkan.particles.fast: particle fast path (3.10), default true. */
    public static boolean particlesFast() {
        return bool(PROP_PARTICLES_FAST, true);
    }

    /** fml.vulkan.particles.sort: sort particles back to front (D10), default false. */
    public static boolean particlesSort() {
        return bool(PROP_PARTICLES_SORT, false);
    }

    /**
     * fml.vulkan.presentMinMs: OPT-IN presentation limiter, minimum ms between presented frames while vsync is off (frames rendered in
     * between are not shown). Default 0 = off: every rendered frame is presented. Never set by the benchmark.
     */
    public static double presentMinMs() {
        String v = raw(PROP_PRESENT_MIN_MS);
        if (v == null || v.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Double.parseDouble(v.trim()));
        } catch (NumberFormatException e) {
            warnInvalid(PROP_PRESENT_MIN_MS, v, "0");
            return 0;
        }
    }

    /**
     * fml.vulkan.macPresenter (macOS): {@code auto} (default) = with vsync off present through the IOSurface layer presenter (every frame is
     * handed to the compositor, no CAMetalLayer drawable wait); with vsync on, or {@code swapchain}, the MoltenVK swapchain is used.
     */
    public static String macPresenter() {
        return choice(PROP_MAC_PRESENTER, "auto", "auto", "swapchain");
    }

    /** fml.vulkan.presenterSurfaces: IOSurfaces of the macOS layer presenter, default 5 (3..8). */
    public static int presenterSurfaces() {
        return Math.max(3, Math.min(8, integer(PROP_PRESENTER_SURFACES, 5)));
    }

    /** fml.vulkan.latency: normal | low (4.2), default normal. */
    public static String latency() {
        return choice(PROP_LATENCY, "normal", "normal", "low");
    }

    /** fml.vulkan.capUnfocused: frame cap while the window is unfocused, default 30. */
    public static int capUnfocused() {
        return Math.max(1, integer(PROP_CAP_UNFOCUSED, 30));
    }

    /** fml.vulkan.capMinimised: frame cap while the window is minimised, default 10. */
    public static int capMinimised() {
        return Math.max(1, integer(PROP_CAP_MINIMISED, 10));
    }

    /** fml.vulkan.downtimeAfterPresent: run after-present work in the frame limiter's sleep (4.3), default false. */
    public static boolean downtimeAfterPresent() {
        return bool(PROP_DOWNTIME_AFTER_PRESENT, false);
    }

    /** fml.vulkan.input.guiPerFrame: poll GUI input once per frame (4.4), default false. */
    public static boolean inputGuiPerFrame() {
        return bool(PROP_INPUT_GUI_PER_FRAME, false);
    }

    /** fml.vulkan.input.restoreHeldKeys: re-press held keys after a focus change (4.4), default false. */
    public static boolean inputRestoreHeldKeys() {
        return bool(PROP_INPUT_RESTORE_HELD_KEYS, false);
    }

    /** fml.vulkan.gpuTimers: per-phase GPU timestamps (4.1), default true. */
    public static boolean gpuTimers() {
        return bool(PROP_GPU_TIMERS, true);
    }

    /** fml.gameDir (set by module G), falling back to user.dir. Absolute and normalized. */
    public static Path gameDir() {
        String v = raw(PROP_GAME_DIR);
        if (v == null) v = System.getProperty("user.dir", ".");
        return Paths.get(v).toAbsolutePath().normalize();
    }

    private static String raw(String key) {
        String v = System.getProperty(key);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private static boolean bool(String key, boolean def) {
        String v = raw(key);
        if (v == null) return def;
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> {
                warnInvalid(key, v, String.valueOf(def));
                yield def;
            }
        };
    }

    private static int integer(String key, int def) {
        String v = raw(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            warnInvalid(key, v, String.valueOf(def));
            return def;
        }
    }

    private static long longValue(String key, long def) {
        String v = raw(key);
        if (v == null) return def;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            warnInvalid(key, v, String.valueOf(def));
            return def;
        }
    }

    private static double decimal(String key, double def) {
        String v = raw(key);
        if (v == null) return def;
        try {
            double d = Double.parseDouble(v);
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new NumberFormatException(v);
            return d;
        } catch (NumberFormatException e) {
            warnInvalid(key, v, String.valueOf(def));
            return def;
        }
    }

    /** Lower-cased value when it is one of {@code allowed}, otherwise {@code def} (warned once). */
    private static String choice(String key, String def, String... allowed) {
        String v = raw(key);
        if (v == null) return def;
        String lower = v.toLowerCase(Locale.ROOT);
        for (String a : allowed) {
            if (a.equals(lower)) return a;
        }
        warnInvalid(key, v, def);
        return def;
    }

    private static void warnInvalid(String key, String value, String used) {
        if (WARNED.add(key + '=' + value)) {
            VkLog.LOGGER.warn("Invalid value '{}' for system property {}, using {}", value, key, used);
        }
    }
}
