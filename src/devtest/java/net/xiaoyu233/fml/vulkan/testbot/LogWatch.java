package net.xiaoyu233.fml.vulkan.testbot;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.regex.Pattern;

/**
 * Watches everything the game prints (System.out/err tee, log4j appender on the root logger) and attributes suspicious lines to the step
 * that is running: GL errors, Vulkan / validation errors, exceptions, "unsupported GL call" warnings of the backend, ERROR level lines.
 * Same design as the LogWatch of the user's Twilight Forest test bot; the known-noise list is the one of this project.
 * <p>
 * MITE redirects System.out/err into log4j (so every printed line reaches the appender as an event); the console appender then prints it
 * again in the {@code [hh:mm:ss] [thread/LEVEL]: text} format. The tee therefore ignores lines in that format (the appender already saw the
 * event, with its logger name and level) and only handles raw prints.
 */
public final class LogWatch {
    private static final Pattern FAILING = Pattern.compile("GL ERROR|Missing resource|issing texture|Using missing|FileNotFoundException|VK_ERROR|VUID-|Validation Error"
        + "|Critical injection failure|InjectionError|Mixin apply failed|NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|AbstractMethodError|UnsatisfiedLinkError"
        + "|OutOfMemoryError|(^|\\s|\\.)\\w*(Exception|Error)\\b.*");
    private static final Pattern SUSPICIOUS = Pattern.compile("\\bERROR\\b|\\bSEVERE\\b|Unable to|Failed to|Could not|Couldn't|Cannot |missingno|not implemented|nsupported|TODO"
        + "|Exception|device lost|DEVICE_LOST");
    private static final Pattern CONSOLE_LINE = Pattern.compile("^\\[\\d\\d:\\d\\d:\\d\\d\\] \\[[^\\]]*/(INFO|WARN|ERROR|FATAL|DEBUG|TRACE)\\]: .*");
    private static volatile Report.Section current;
    private static volatile boolean installed;
    private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<>();
    private static final ThreadLocal<Noise> NOISE = ThreadLocal.withInitial(Noise::new);

    private LogWatch() {
    }

    static void setCurrent(Report.Section s) {
        current = s;
    }

    // ------------------------------------------------------------------ known noise (pre-existing, not caused by the Vulkan backend)

    /**
     * Log lines that bury real findings and have nothing to do with the port (each one is counted in the report as "ignored", never dropped silently):
     * <ul>
     * <li>{@code Writing reference files... [failed]} + {@code StringIndexOutOfBoundsException: Range [5, 2)}: MITE's ReferenceFileWriter.writeItemReachFile
     * cuts the localized name of iron tools with {@code substring(5)} and gets a 2 character name (translation lookup of FML's language loader, resource pack +
     * en_US); the exception and its stack follow the message on the same thread.</li>
     * <li>{@code Realms: Server not available!}: the Realms thread of vanilla MITE has no network in the dev run.</li>
     * <li>{@code ... not found in sound pool!}: the dev run directory has no sound assets; {@code Resource not found: lang/zh_CN.*}: the language of options.txt has no file.</li>
     * <li>{@code Resource not found: lang/en_US.json} / {@code lang/MITE.json}: FML's language loader probes for json language files the resource packs do not have.</li>
     * <li>{@code Warning: Not all pending chunks were saved to disk!}: printed by MITE's chunk loader whenever the integrated server stops with queued saves.</li>
     * <li>{@code Error processing element Queue ...: CLASS_NOT_FOUND}: dev log4j2.xml uses an appender type log4j-core does not know (harmless).</li>
     * </ul>
     */
    private static String knownNoiseKind(String line) {
        if (line.contains("Writing reference files... [failed]")) {
            return "MITE ReferenceFileWriter.writeItemReachFile: StringIndexOutOfBounds in substring(5) (iron tool name, pre-existing, not rendering related)";
        }
        if (line.contains("Realms: Server not available!")) {
            return "Realms: Server not available (no network in the dev run)";
        }
        if (line.contains("not found in sound pool!")) {
            return "SoundManager: sound event not found in sound pool (the dev run has no sound assets, pre-existing)";
        }
        if (line.contains("Resource not found: lang/zh_CN.json") || line.contains("Resource not found: lang/zh_CN.lang")) {
            return "FML language loader: Resource not found lang/zh_CN.json|lang (options.txt language without a file, pre-existing)";
        }
        if (line.contains("Resource not found: lang/en_US.json") || line.contains("Resource not found: lang/MITE.json")) {
            return "FML language loader: Resource not found lang/en_US.json|MITE.json (pre-existing)";
        }
        if (line.contains("Not all pending chunks were saved to disk")) {
            return "MITE chunk loader at integrated server stop: Not all pending chunks were saved to disk";
        }
        if (line.contains("Error processing element Queue")) {
            return "dev log4j2.xml: Queue element CLASS_NOT_FOUND (harmless)";
        }
        return null;
    }

    /** Per thread: the message and the following printStackTrace run on one thread, so the association is kept per thread. */
    private static final class Noise {
        /** a known noise message was the last thing this thread logged: an exception line right after it belongs to it */
        boolean armed;
        boolean inStack;
    }

    private static boolean isStackLine(String line) {
        String t = line.stripLeading();
        return t.startsWith("at ") || t.startsWith("... ") && t.endsWith(" more") || t.startsWith("Caused by:");
    }

    /** @return true when the line was consumed as known noise (or as part of its exception) and must not be looked at any further */
    private static boolean consumeKnownNoise(String line) {
        Noise n = NOISE.get();
        String kind = knownNoiseKind(line);
        if (kind != null) {
            n.armed = true;
            n.inStack = false;
            Report.Section s = current;
            if (s != null) {
                s.ignoredNoise(kind);
            }
            return true;
        }
        boolean exceptionLine = line.matches("^(EXCEPTION )?(\\w+\\.)+\\w*(Exception|Error)(:.*)?$");
        if (n.armed && exceptionLine && !n.inStack) {
            n.inStack = true;
            return true;
        }
        if (n.inStack && (isStackLine(line) || line.isBlank())) {
            return true;
        }
        n.inStack = false;
        if (!line.isBlank() && !isStackLine(line)) {
            n.armed = false;
        }
        return false;
    }

    static boolean isFailing(String line) {
        return FAILING.matcher(line).find();
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        System.setOut(new PrintStream(new Tee(System.out), true));
        System.setErr(new PrintStream(new Tee(System.err), true));
        try {
            installLog4j();
        } catch (Throwable t) {
            System.err.println("[VkTestBot] log4j appender not installed: " + t);
        }
    }

    private static void installLog4j() {
        org.apache.logging.log4j.core.LoggerContext ctx = (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        org.apache.logging.log4j.core.config.Configuration cfg = ctx.getConfiguration();
        org.apache.logging.log4j.core.appender.AbstractAppender app = new org.apache.logging.log4j.core.appender.AbstractAppender(
            "VkTestBotWatch", null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override
            public void append(org.apache.logging.log4j.core.LogEvent event) {
                String msg = event.getMessage().getFormattedMessage();
                boolean warnOrWorse = event.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.WARN);
                String logger = event.getLoggerName();
                boolean backend = logger != null && logger.startsWith("FML-Vulkan");
                // the backend's own warnings/errors (unsupported GL call, pipeline failures, validation messages) are always findings
                if (warnOrWorse && backend && msg.startsWith("[Vulkan")) {
                    NativeChecks.onVulkanMessage(msg);
                }
                handle((warnOrWorse && backend ? "[FML-Vulkan] " : "") + event.getLevel().name() + " " + msg, warnOrWorse && backend);
                Throwable t = event.getThrown();
                if (t != null) {
                    handle("EXCEPTION " + t, false);
                }
            }
        };
        app.start();
        cfg.getRootLogger().addAppender(app, null, null);
        ctx.updateLoggers();
    }

    static void handle(String line, boolean force) {
        if (line == null || line.isEmpty() || line.contains("[VkTestBot]")) {
            return;
        }
        if (Boolean.TRUE.equals(BUSY.get())) {
            return;
        }
        BUSY.set(Boolean.TRUE);
        try {
            // appender events carry the level as a prefix ("ERROR text"): look at the text for the noise / stack decisions
            String text = line;
            int sp = line.indexOf(' ');
            if (sp > 0 && sp <= 6 && line.substring(0, sp).chars().allMatch(Character::isUpperCase)) {
                text = line.substring(sp + 1);
            }
            if (consumeKnownNoise(text)) {
                return;
            }
            if (isStackLine(text)) {
                return;
            }
            if (force || FAILING.matcher(line).find() || SUSPICIOUS.matcher(line).find()) {
                record(line);
            }
        } finally {
            BUSY.set(null);
        }
    }

    private static void record(String line) {
        String text = line.length() > 400 ? line.substring(0, 400) + "..." : line;
        Report.Section s = current;
        if (s != null) {
            s.logHit(text);
        } else {
            TestBot.noteStartupLog(text);
        }
    }

    private static final class Tee extends OutputStream {
        private final PrintStream target;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        Tee(PrintStream target) {
            this.target = target;
        }

        @Override
        public synchronized void write(int b) {
            target.write(b);
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                line.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            target.write(b, off, len);
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') {
                    flushLine();
                } else if (b[i] != '\r') {
                    line.write(b[i]);
                }
            }
        }

        @Override
        public void flush() {
            target.flush();
        }

        private void flushLine() {
            String s = new String(line.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            line.reset();
            if (!CONSOLE_LINE.matcher(s).matches()) {
                handle(s, false);
            }
        }
    }
}
