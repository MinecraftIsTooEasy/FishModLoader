package net.xiaoyu233.fml.relaunch;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * JVM flag presets for the client, selected with {@code -Dfml.jvm.preset=<name>} or the environment variable
 * {@code FML_JVM_PRESET} (the system property wins). Presets:
 * <ul>
 *   <li>{@code default}: no extra flags (the command line is exactly the one the launcher started);</li>
 *   <li>{@code low-latency}: ZGC, no AlwaysPreTouch, no string deduplication, heap fixed to min(4 GiB, 25% RAM);</li>
 *   <li>{@code balanced}: G1 with a 20 ms pause target and tuned young generation and reference processing;</li>
 *   <li>{@code server}: ParallelGC.</li>
 * </ul>
 * Flags already given on the command line are never overridden: an option with the same name is left alone, a GC is only
 * chosen by the preset when the command line does not select one, and {@code -Xms}/{@code -Xmx} are both skipped when the
 * command line sets either. An unknown preset name falls back to {@code default} with a warning.
 * <p>
 * A JVM cannot change its own flags, so a preset with flags to add restarts the client once with them ({@link #relaunch}),
 * the same way {@link MacFirstThread} restarts for {@code -XstartOnFirstThread}: the child gets the same arguments plus
 * {@code -Dfml.jvm.presetApplied=true}, inherits the IO and its exit code is propagated. The child does not restart again.
 * <p>
 * AppClassLoader side: no log4j, no LWJGL, no backend classes; messages go to stdout/stderr with the {@code [FML]} prefix.
 */
public final class JvmPresets {
    public static final String PROP_PRESET = "fml.jvm.preset";
    public static final String ENV_PRESET = "FML_JVM_PRESET";
    public static final String PROP_APPLIED = "fml.jvm.presetApplied";
    public static final String DEFAULT = "default";

    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;
    private static final long LOW_LATENCY_HEAP_CAP = 4 * GIB;
    private static final long MIN_SIZED_HEAP = 512 * MIB;

    private static final Map<String, String> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put(DEFAULT, "no extra JVM flags");
        PRESETS.put("low-latency", "ZGC, fixed heap of min(4 GiB, 25% RAM), no AlwaysPreTouch, no string deduplication");
        PRESETS.put("balanced", "G1 with a 20 ms pause target, G1NewSizePercent=30, G1ReservePercent=20, ParallelRefProcEnabled");
        PRESETS.put("server", "ParallelGC");
    }

    /** One preset flag. {@code gcSpecific} flags only make sense for the GC the preset selects. */
    private record Flag(String text, boolean gcSpecific) {
    }

    private JvmPresets() {
    }

    /** The preset name from {@code -Dfml.jvm.preset}, else {@code FML_JVM_PRESET}, else {@code default}. Unknown names fall back to default. */
    public static String selectPreset() {
        String value = System.getProperty(PROP_PRESET);
        String source = "-D" + PROP_PRESET;
        if (value == null || value.isBlank()) {
            value = System.getenv(ENV_PRESET);
            source = "env " + ENV_PRESET;
        }
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String name = value.trim().toLowerCase(Locale.ROOT);
        if (PRESETS.containsKey(name)) {
            return name;
        }
        warn("unknown JVM preset '" + value.trim() + "' (from " + source + "), falling back to '" + DEFAULT + "'. Known presets: "
                + String.join(", ", PRESETS.keySet()));
        return DEFAULT;
    }

    /**
     * The JVM flags the preset adds to the command line of this JVM, without the flags the command line already sets.
     * Empty for {@code default}, for an unknown name (which falls back to default) and inside a child of a preset relaunch.
     * Logs the chosen flags at INFO.
     */
    public static List<String> args(String preset) {
        String name = normalize(preset);
        if (name.equals(DEFAULT)) {
            info("preset '" + DEFAULT + "': no extra JVM flags, JVM arguments: " + String.join(" ", inputArguments()));
            return List.of();
        }

        if (Boolean.getBoolean(PROP_APPLIED)) {
            // Child of relaunch(): the flags are already on the command line
            watchParent();
            info("preset '" + name + "' active, JVM arguments: " + String.join(" ", inputArguments()));
            return List.of();
        }

        List<String> input = inputArguments();
        List<String> chosen = select(presetFlags(name), input, name);
        if (chosen.isEmpty()) {
            info("preset '" + name + "': nothing to add, the command line already sets every flag of the preset");
        } else {
            info("preset '" + name + "' adds JVM flags: " + String.join(" ", chosen));
        }
        return chosen;
    }

    /**
     * Entry point for the client {@code Main}: selects the preset, and when it adds flags restarts the JVM with them and exits
     * with the exit code of the child. Returns normally for {@code default}, for a preset without flags to add, and in a child
     * that was already restarted by a preset.
     */
    public static void apply(String[] args) {
        List<String> presetArgs = args(selectPreset());
        if (presetArgs.isEmpty()) {
            return;
        }
        OptionalInt exitCode = relaunch(presetArgs, args);
        if (exitCode.isPresent()) {
            System.exit(exitCode.getAsInt());
        }
    }

    /**
     * Restarts the client JVM with the preset flags in front of the arguments of this JVM, runs the game in the child and
     * returns its exit code. Empty when the child could not be started: the caller then continues in this JVM.
     */
    public static OptionalInt relaunch(List<String> presetArgs, String[] args) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(presetArgs);
        for (String argument : inputArguments()) {
            if (argument.startsWith("-agentlib:jdwp") || argument.startsWith("-Xrunjdwp")) {
                continue; // a fixed debug port would collide with the one of this process
            }
            command.add(argument);
        }
        command.add("-D" + PROP_APPLIED + "=true");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("net.xiaoyu233.fml.relaunch.client.Main");
        Collections.addAll(command, args);

        Process child;
        try {
            child = new ProcessBuilder(command).inheritIO().start();
        } catch (IOException | RuntimeException e) {
            warn("cannot restart the JVM with the preset flags, continuing without them: " + e);
            return OptionalInt.empty();
        }

        info("restarting the JVM with the preset flags");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> terminate(child), "FML-Preset-Cleanup"));

        int exitCode;
        try {
            exitCode = child.waitFor();
        } catch (InterruptedException e) {
            terminate(child);
            Thread.currentThread().interrupt();
            exitCode = 1;
        }
        return OptionalInt.of(exitCode);
    }

    /** The flags of a preset for the given (known) name, with the heap size computed from the physical memory. */
    private static List<Flag> presetFlags(String name) {
        List<Flag> flags = new ArrayList<>();
        switch (name) {
            case "low-latency" -> {
                flags.add(new Flag("-XX:+UseZGC", true));
                flags.add(new Flag("-XX:-AlwaysPreTouch", false));
                flags.add(new Flag("-XX:-UseStringDeduplication", false));
                long heap = Math.min(LOW_LATENCY_HEAP_CAP, physicalMemory() / 4);
                if (heap >= MIN_SIZED_HEAP) {
                    String size = (heap / MIB) + "m";
                    flags.add(new Flag("-Xms" + size, false));
                    flags.add(new Flag("-Xmx" + size, false));
                } else {
                    warn("preset 'low-latency': physical memory unknown or too small for a fixed heap, the heap is not set");
                }
            }
            case "balanced" -> {
                flags.add(new Flag("-XX:+UseG1GC", true));
                flags.add(new Flag("-XX:MaxGCPauseMillis=20", true));
                // G1NewSizePercent is experimental on JDK 25: the unlock must come before it on the command line
                flags.add(new Flag("-XX:+UnlockExperimentalVMOptions", true));
                flags.add(new Flag("-XX:G1NewSizePercent=30", true));
                flags.add(new Flag("-XX:G1ReservePercent=20", true));
                flags.add(new Flag("-XX:+ParallelRefProcEnabled", true));
            }
            case "server" -> flags.add(new Flag("-XX:+UseParallelGC", true));
            default -> {
                // DEFAULT: no flags
            }
        }
        return flags;
    }

    /**
     * The preset flags that do not clash with the command line: an option the command line sets is skipped, GC-specific flags
     * are skipped when the command line selects a GC, and the heap flags are skipped when it sets -Xms or -Xmx.
     */
    private static List<String> select(List<Flag> wanted, List<String> input, String preset) {
        Set<String> userOptions = new HashSet<>();
        boolean userGc = false;
        boolean userHeap = false;
        for (String argument : input) {
            String option = optionName(argument);
            if (option == null) {
                continue;
            }
            userOptions.add(option);
            if (option.startsWith("Use") && option.endsWith("GC")) {
                userGc = true;
            }
            if (option.equals("Xms") || option.equals("Xmx")) {
                userHeap = true;
            }
        }

        List<String> chosen = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Flag flag : wanted) {
            String option = optionName(flag.text());
            boolean clash = (flag.gcSpecific() && userGc)
                    || ((option.equals("Xms") || option.equals("Xmx")) && userHeap)
                    || userOptions.contains(option);
            if (clash) {
                skipped.add(flag.text());
            } else {
                chosen.add(flag.text());
            }
        }
        if (!skipped.isEmpty()) {
            info("preset '" + preset + "': not overriding flags already on the command line: " + String.join(" ", skipped));
        }
        return chosen;
    }

    /**
     * The option name of a JVM argument: {@code -XX:+UseZGC} gives {@code UseZGC}, {@code -XX:MaxGCPauseMillis=20} gives
     * {@code MaxGCPauseMillis}, {@code -Xmx4g} gives {@code Xmx}. Null for other arguments.
     */
    static String optionName(String argument) {
        if (argument.startsWith("-XX:")) {
            String option = argument.substring(4);
            if (option.startsWith("+") || option.startsWith("-")) {
                option = option.substring(1);
            }
            int eq = option.indexOf('=');
            return eq >= 0 ? option.substring(0, eq) : option;
        }
        if (argument.startsWith("-Xms")) {
            return "Xms";
        }
        if (argument.startsWith("-Xmx")) {
            return "Xmx";
        }
        return null;
    }

    private static String normalize(String preset) {
        if (preset == null || preset.isBlank()) {
            return DEFAULT;
        }
        String name = preset.trim().toLowerCase(Locale.ROOT);
        if (PRESETS.containsKey(name)) {
            return name;
        }
        warn("unknown JVM preset '" + preset.trim() + "', falling back to '" + DEFAULT + "'");
        return DEFAULT;
    }

    private static List<String> inputArguments() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments();
    }

    /** Total physical memory in bytes, 0 when unknown. */
    private static long physicalMemory() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize();
            }
        } catch (RuntimeException | LinkageError e) {
            // unknown: the heap is not fixed
        }
        return 0L;
    }

    /** The java executable of this JVM (the same JDK, as chosen by the launcher). */
    private static String javaExecutable() {
        var current = ProcessHandle.current().info().command();
        if (current.isPresent() && Files.isExecutable(Paths.get(current.get()))) {
            return current.get();
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            var java = Paths.get(javaHome, "bin", "java");
            if (Files.isExecutable(java)) {
                return java.toString();
            }
        }
        return "java";
    }

    private static void terminate(Process child) {
        if (!child.isAlive()) {
            return;
        }
        child.destroy();
        try {
            if (!child.waitFor(5, TimeUnit.SECONDS)) {
                child.destroyForcibly();
            }
        } catch (InterruptedException e) {
            child.destroyForcibly();
        }
    }

    /** Child side: when the parent disappears without waiting for us (killed by the launcher) the game exits too. */
    private static void watchParent() {
        try {
            ProcessHandle.current().parent().ifPresent(parent -> parent.onExit().thenRun(() -> {
                Thread halter = new Thread(() -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ignored) {
                    }
                    Runtime.getRuntime().halt(1);
                }, "FML-Preset-Halt");
                halter.setDaemon(true);
                halter.start();
                System.exit(1);
            }));
        } catch (RuntimeException ignored) {
            // no process information available: nothing to watch
        }
    }

    private static void info(String message) {
        System.out.println("[FML] INFO JVM " + message);
    }

    private static void warn(String message) {
        System.err.println("[FML] WARN JVM " + message);
    }
}
