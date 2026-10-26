package net.xiaoyu233.fml.relaunch;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * macOS: GLFW (Cocoa) may only be used from the first thread of the process, and the game loop runs on the JVM main thread,
 * so the JVM has to be started with {@code -XstartOnFirstThread}. When it was not (the launcher does not know about it), the
 * client {@code Main} restarts itself with that option and the same arguments: IO is inherited, the exit code is propagated.
 * <p>
 * Guards: the child gets {@code -Dfml.vulkan.relaunched=true} and fails with a clear message instead of forking again when it
 * still is not on the first thread; {@code -Dfml.vulkan.noRelaunch=true} (or {@code noRelaunch=true} in
 * {@code config/fml-vulkan.properties}) skips the restart. A parent that is killed or terminated takes the child with it.
 * <p>
 * AppClassLoader side: no log4j, no LWJGL, no backend classes (the parent process must stay minimal).
 */
public final class MacFirstThread {
    public static final String PROP_RELAUNCHED = "fml.vulkan.relaunched";
    public static final String PROP_NO_RELAUNCH = "fml.vulkan.noRelaunch";

    private static final String START_ON_FIRST_THREAD = "-XstartOnFirstThread";
    private static final String ENABLE_NATIVE_ACCESS = "--enable-native-access";
    private static final String UNSAFE_MEMORY_ACCESS = "--sun-misc-unsafe-memory-access";

    private MacFirstThread() {
    }

    /**
     * Returns when this JVM runs the game on the first thread (or the restart is disabled / impossible). Otherwise restarts the
     * JVM and exits this process with the exit code of the child; this call then never returns.
     */
    public static void ensureFirstThread(String[] args) {
        if (isOnFirstThread()) {
            if (isTrue(System.getProperty(PROP_RELAUNCHED))) {
                exitWhenParentDies();
            }
            return;
        }

        if (isTrue(System.getProperty(PROP_RELAUNCHED))) {
            throw new IllegalStateException("FishModLoader restarted the JVM with " + START_ON_FIRST_THREAD + ", but the process is still not on"
                    + " the first thread (env JAVA_STARTED_ON_FIRST_THREAD_" + ProcessHandle.current().pid() + " is not 1). GLFW cannot run"
                    + " in this JVM; check that the launcher does not strip the JVM argument.");
        }

        if (isTrue(System.getProperty(PROP_NO_RELAUNCH))) {
            System.err.println("[FML] The JVM was not started with " + START_ON_FIRST_THREAD + " and the restart is disabled (-D" + PROP_NO_RELAUNCH
                    + "=true): GLFW will fail to create the window unless " + START_ON_FIRST_THREAD + " is passed some other way.");
            return;
        }

        Process child;
        try {
            child = start(args);
        } catch (IOException | RuntimeException e) {
            System.err.println("[FML] Cannot restart the JVM with " + START_ON_FIRST_THREAD + ", continuing in this process"
                    + " (GLFW needs the first thread on macOS, add the JVM argument " + START_ON_FIRST_THREAD + " to the launcher): " + e);
            return;
        }

        // The launcher only knows this process: when it terminates it, the game must not keep running as an orphan
        Runtime.getRuntime().addShutdownHook(new Thread(() -> terminate(child), "FML-Relaunch-Cleanup"));

        int exitCode;
        try {
            exitCode = child.waitFor();
        } catch (InterruptedException e) {
            terminate(child);
            Thread.currentThread().interrupt();
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    /** True when the JVM was started with -XstartOnFirstThread (the java launcher exports JAVA_STARTED_ON_FIRST_THREAD_&lt;pid&gt;=1). */
    static boolean isOnFirstThread() {
        return "1".equals(System.getenv("JAVA_STARTED_ON_FIRST_THREAD_" + ProcessHandle.current().pid()));
    }

    private static Process start(String[] args) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(buildCommand(args));
        builder.inheritIO();
        return builder.start();
    }

    static List<String> buildCommand(String[] args) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add(START_ON_FIRST_THREAD);

        boolean nativeAccess = false;
        boolean unsafeAccess = false;
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argument.equals(START_ON_FIRST_THREAD)) {
                continue;
            }
            if (argument.startsWith("-agentlib:jdwp") || argument.startsWith("-Xrunjdwp")) {
                continue; // a fixed debug port would collide with the one of this process
            }
            if (argument.startsWith(ENABLE_NATIVE_ACCESS)) {
                nativeAccess = true;
            } else if (argument.startsWith(UNSAFE_MEMORY_ACCESS)) {
                unsafeAccess = true;
            }
            command.add(argument);
        }

        command.add("-D" + PROP_RELAUNCHED + "=true");
        if (!nativeAccess) {
            // LWJGL and the FFM based OpenAL compat call restricted methods; JDK 24+ warns for every one of them otherwise
            command.add(ENABLE_NATIVE_ACCESS + "=ALL-UNNAMED");
        }
        if (!unsafeAccess) {
            // LWJGL 3.4's default memory backend uses sun.misc.Unsafe: JDK 24+ prints a deprecation warning on every launch and a
            // later JDK denies it by default. The option exists since JDK 23, the FML baseline is JDK 25
            command.add(UNSAFE_MEMORY_ACCESS + "=allow");
        }

        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("net.xiaoyu233.fml.relaunch.client.Main");
        for (String arg : args) {
            command.add(arg);
        }
        return command;
    }

    /** The java executable of this JVM (the same JDK, and javaw/java as chosen by the launcher). */
    private static String javaExecutable() {
        Optional<String> current = ProcessHandle.current().info().command();
        if (current.isPresent() && Files.isExecutable(Paths.get(current.get()))) {
            return current.get();
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path java = Paths.get(javaHome, "bin", "java");
            if (Files.isExecutable(java)) {
                return java.toString();
            }
        }
        return "java"; // PATH lookup, last resort
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
    private static void exitWhenParentDies() {
        try {
            ProcessHandle.current().parent().ifPresent(parent -> parent.onExit().thenRun(() -> {
                Thread halter = new Thread(() -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ignored) {
                    }
                    Runtime.getRuntime().halt(1);
                }, "FML-Relaunch-Halt");
                halter.setDaemon(true);
                halter.start();
                System.exit(1);
            }));
        } catch (RuntimeException ignored) {
            // no process information available: nothing to watch
        }
    }

    private static boolean isTrue(String value) {
        if (value == null) {
            return false;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
