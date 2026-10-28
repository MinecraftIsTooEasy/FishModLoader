package net.xiaoyu233.fml.relaunch;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads the Vulkan backend options from {@code <gameDir>/config/fml-vulkan.properties} into system properties
 * ({@code key=value} becomes {@code fml.vulkan.<key>=value}). Only keys that are not set yet are applied, so
 * {@code -Dfml.vulkan.<key>=...} on the command line always wins. A commented default file is created when missing.
 * <p>
 * The file is optional: every I/O problem is reported on stderr and ignored, it never prevents the game from starting.
 * It is parsed by hand instead of {@link java.util.Properties} because {@code Properties} reads ISO-8859-1 and treats the
 * backslash as an escape character (which destroys Windows paths such as {@code captureDir=C:\Users\me\caps} and
 * non-ASCII values such as world names). Here the file is UTF-8 (a BOM is accepted), values are taken literally and trimmed.
 * AppClassLoader side: no log4j, no LWJGL, no backend classes.
 */
public final class VulkanConfigLoader {
    private static final String CONFIG_FILE = "config/fml-vulkan.properties";
    private static final String PROPERTY_PREFIX = "fml.vulkan.";

    private static final String DEFAULT_CONFIG = String.join("\n",
            "# FishModLoader Vulkan configuration",
            "# This file is read before the game starts: every <key>=<value> line becomes the system property fml.vulkan.<key>.",
            "# -Dfml.vulkan.<key>=<value> on the command line overrides the values in this file.",
            "# The file is UTF-8; values are used literally (a backslash in a Windows path needs no escaping) and trimmed.",
            "",
            "# VSync and present mode",
            "# vsync: true or false (default: follows Display.setVSyncEnabled)",
            "# presentMode: auto | fifo | mailbox | immediate (auto: vsync -> FIFO, otherwise MAILBOX -> IMMEDIATE -> FIFO)",
            "#vsync=true",
            "#presentMode=auto",
            "",
            "# Frames in flight (1..3, default 2)",
            "#framesInFlight=2",
            "",
            "# macOS: full-resolution (Retina) framebuffer",
            "#retina=true",
            "",
            "# Vulkan validation layer (needs the Vulkan SDK) and debug output",
            "#validation=false",
            "#debug=false",
            "",
            "# Vulkan loader library. By default Windows uses the system loader (vulkan-1.dll) and macOS uses the bundled MoltenVK.",
            "# Set a full path (or library name) to use another loader or MoltenVK build.",
            "#library=",
            "",
            "# macOS: GLFW needs the first thread, so FML restarts the JVM with -XstartOnFirstThread when it was not started that way.",
            "# Set to true when the launcher already passes -XstartOnFirstThread or when you want to attach a debugger.",
            "#noRelaunch=false",
            "",
            "# Frame capture: comma separated presented-frame numbers, written as PNG into captureDir (relative to the game directory)",
            "#capture=60,300",
            "#captureDir=vk-captures",
            "",
            "# Test harness: load (or create) this singleplayer world from the main menu (autoWorldSeed fixes the seed of a new world),",
            "# quit after N presented frames",
            "#autoWorld=vktest",
            "#autoWorldSeed=12345",
            "#exitAfterFrames=420",
            "",
            "# Input and window",
            "# borderless: fullscreen as a borderless window covering the monitor (the default on Windows). On macOS the default is a window",
            "# attached to the monitor (it hides the menu bar and the Dock); set borderless=true there to use a plain borderless window.",
            "#rawMouse=true",
            "#borderless=true",
            "",
            "# Non-render optimisations (areas: net, regionio, entity, input, fps, quality, reload). Documented in vulkan/opt/OptFlags.",
            "# Areas are switched off automatically when a conflicting mod (MithrilCore, Better Game Setting, ...) is present.",
            "#opt=true",
            "#opt.quality=true",
            "#opt.workers=2",
            "");

    private VulkanConfigLoader() {
    }

    /**
     * Applies {@code config/fml-vulkan.properties} of the given game directory. Never throws.
     */
    public static void loadConfig(Path gameDir) {
        Path configPath = gameDir.resolve(CONFIG_FILE);

        if (!Files.exists(configPath)) {
            try {
                createDefaultConfig(configPath);
            } catch (IOException | RuntimeException e) {
                System.err.println("[FML] Cannot create " + configPath + ": " + e);
            }
            return; // the default file contains comments only
        }

        Map<String, String> entries;
        try {
            entries = readEntries(configPath);
        } catch (IOException | RuntimeException e) {
            // MalformedInputException (not valid UTF-8) is an IOException as well
            System.err.println("[FML] Cannot read " + configPath + " (it must be UTF-8): " + e);
            return;
        }

        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String property = PROPERTY_PREFIX + entry.getKey();
            if (System.getProperty(property) == null) {
                System.setProperty(property, entry.getValue());
            }
        }
    }

    /** Parses {@code key=value} lines in file order (the last occurrence of a key wins). Blank values are skipped. */
    static Map<String, String> readEntries(Path configPath) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                        line = line.substring(1); // UTF-8 BOM written by Windows editors
                    }
                }
                parseLine(line, entries);
            }
        }
        return entries;
    }

    private static void parseLine(String line, Map<String, String> entries) {
        line = line.trim();
        if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '!') {
            return;
        }

        // The key ends at the first '=', ':' or whitespace (java.util.Properties syntax), but nothing is unescaped
        int keyEnd = 0;
        while (keyEnd < line.length()) {
            char c = line.charAt(keyEnd);
            if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                break;
            }
            keyEnd++;
        }

        String key = line.substring(0, keyEnd);
        if (key.isEmpty()) {
            return;
        }

        int valueStart = keyEnd;
        while (valueStart < line.length() && Character.isWhitespace(line.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart < line.length() && (line.charAt(valueStart) == '=' || line.charAt(valueStart) == ':')) {
            valueStart++;
        }

        String value = line.substring(Math.min(valueStart, line.length())).trim();
        if (!value.isEmpty()) {
            entries.put(key, value);
        }
    }

    private static void createDefaultConfig(Path configPath) throws IOException {
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(configPath, DEFAULT_CONFIG, StandardCharsets.UTF_8);
    }
}
