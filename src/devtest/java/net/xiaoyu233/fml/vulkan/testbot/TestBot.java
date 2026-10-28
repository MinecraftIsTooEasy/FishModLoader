package net.xiaoyu233.fml.vulkan.testbot;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import org.lwjgl.input.Mouse;
import net.xiaoyu233.fml.vulkan.window.NativeWindow;

/**
 * Infrastructure of the dev-only client test bot of the Vulkan port, modelled on the test bot of the user's Twilight Forest port
 * (mite-twilightforest/src/devtest): the script ({@link TestBotScript}) runs linearly on its own thread and hands work to the game threads: client
 * tick (end of runTick), post render (end of a game loop iteration: perf sample + render tasks) and integrated server tick. Results go to
 * {@code <docs>/testbot/REPORT-<tag>.md}, screenshots to {@code <docs>/screenshots/<tag>/}, perf CSV to {@code <docs>/perf/} (all OUTSIDE the repository:
 * -Pfml.vulkan.testbot.docs is set by the runClient task).
 */
public final class TestBot {
    public static final boolean ENABLED = Boolean.getBoolean("fml.vulkan.testbot");
    public static final String PREFIX = "[VkTestBot] ";
    public static final String TAG = System.getProperty("fml.vulkan.testbot.tag", "run");

    private static volatile TestBot instance;
    private static final List<String> STARTUP_LOG = new ArrayList<>();

    final Minecraft mc;
    final Report report;
    final File reportFile;
    final Path shots;
    final PerfLog perf;

    private final Queue<Task<?>> clientTasks = new ConcurrentLinkedQueue<>();
    private final Queue<Task<?>> renderTasks = new ConcurrentLinkedQueue<>();
    private final Queue<Task<?>> serverTasks = new ConcurrentLinkedQueue<>();
    private volatile Runnable serverHook;
    private volatile Runnable clientHook;
    private volatile long frames;
    private volatile long ticks;
    private volatile long serverTicks;
    private volatile boolean aborted;
    private int consecutiveServerTimeouts;
    private Thread thread;

    private TestBot(Minecraft mc) {
        this.mc = mc;
        File docs = new File(System.getProperty("fml.vulkan.testbot.docs", "../docs"));
        this.reportFile = new File(new File(docs, "testbot"), "REPORT-" + TAG + ".md");
        this.shots = VulkanConfig.captureDir();
        this.report = new Report(reportFile, TAG);
        this.perf = new PerfLog(new File(docs, "perf"), TAG, mc);
        try {
            Files.createDirectories(shots);
        } catch (Exception e) {
            System.err.println(PREFIX + "cannot create " + shots + ": " + e);
        }
    }

    /** The running bot (created by the first client tick) or null. */
    public static TestBot get() {
        return instance;
    }

    private volatile Stage stage;

    /** The scripted world (null until the script thread started). */
    public Stage stage() {
        return stage;
    }

    void setStage(Stage s) {
        this.stage = s;
    }

    public Minecraft minecraft() {
        return mc;
    }

    public Report report() {
        return report;
    }

    public static void log(String msg) {
        System.out.println(PREFIX + msg);
    }

    static synchronized void noteStartupLog(String line) {
        if (STARTUP_LOG.size() < 200) {
            STARTUP_LOG.add(line);
        }
    }

    static synchronized List<String> startupLog() {
        return new ArrayList<>(STARTUP_LOG);
    }

    // ------------------------------------------------------------------ hooks (game threads)

    public static void onClientTick(Minecraft mc) {
        if (!ENABLED) {
            return;
        }
        TestBot bot = instance;
        if (bot == null) {
            synchronized (TestBot.class) {
                if (instance == null) {
                    instance = new TestBot(mc);
                    instance.start();
                }
                bot = instance;
            }
        }
        bot.ticks++;
        bot.keepUnfocusedFriendly();
        Runnable ch = bot.clientHook;
        if (ch != null) {
            try {
                ch.run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        bot.drain(bot.clientTasks);
    }

    /** End of a game loop iteration (the frame that will be presented at the start of the next one is fully recorded). */
    public static void onPostRender(Minecraft mc) {
        TestBot bot = instance;
        if (bot == null) {
            return;
        }
        bot.frames++;
        bot.perf.onFrame(mc);
        bot.drain(bot.renderTasks);
    }

    public static void onServerTick(MinecraftServer server) {
        TestBot bot = instance;
        if (bot == null) {
            return;
        }
        bot.serverTicks++;
        Runnable sh = bot.serverHook;
        if (sh != null) {
            try {
                sh.run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        bot.drain(bot.serverTasks);
    }

    /** Hook that runs on EVERY integrated server tick (end of the tick) until replaced / cleared with null. Used by the flight driver. */
    public void setServerHook(Runnable r) {
        serverHook = r;
    }

    /** Hook that runs on EVERY client tick (end of runTick) until replaced / cleared with null. */
    public void setClientHook(Runnable r) {
        clientHook = r;
    }

    // ------------------------------------------------------------------ progress heartbeat and hang diagnostics

    private volatile long lastProgressMs = System.currentTimeMillis();
    private volatile String lastStatus = "starting";
    private long lastStatusLogMs;
    private boolean stallDumped;

    /** Marks forward progress (a step began, an item was handled, a screenshot was taken). Cheap, callable from any thread. */
    void progress(String what) {
        lastProgressMs = System.currentTimeMillis();
        lastStatus = what;
        stallDumped = false;
    }

    /**
     * Daemon watchdog: the report file is rewritten at most every 20 s while the bot makes progress (its "Updated" line is the external hang signal), and
     * when nothing happened for 5 minutes the state of every thread is dumped once into the log so a hang can be diagnosed afterwards.
     */
    private void startWatchdog() {
        Thread w = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    return;
                }
                perf.flush();
                long idle = System.currentTimeMillis() - lastProgressMs;
                if (idle < 60_000) {
                    report.writeThrottled(20_000);
                } else if (idle >= 300_000 && !stallDumped) {
                    stallDumped = true;
                    StringBuilder sb = new StringBuilder();
                    sb.append(PREFIX).append("NO PROGRESS for ").append(idle / 1000).append(" s (last status: ").append(lastStatus).append(") - thread dump:\n");
                    for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                        Thread t = e.getKey();
                        if (t.getName().startsWith("Minecraft main thread") || t.getName().startsWith("Server thread") || t.getName().startsWith("VkTestBot-script")) {
                            sb.append("  \"").append(t.getName()).append("\" ").append(t.getState()).append("\n");
                            int n = 0;
                            for (StackTraceElement el : e.getValue()) {
                                sb.append("      at ").append(el).append("\n");
                                if (++n >= 25) {
                                    break;
                                }
                            }
                        }
                    }
                    System.out.println(sb);
                    report.setFatal("no progress for " + idle / 1000 + " s during '" + lastStatus + "' (thread dump in the client log)");
                    report.write();
                }
            }
        }, "VkTestBot-watchdog");
        w.setDaemon(true);
        w.start();
    }

    private boolean windowPlaced;
    private int mouseGrabWarnings;

    /**
     * The user keeps working on the machine: the game must keep running without window focus (pauseOnLostFocus off, re-asserted every tick because the
     * options screen / options.txt can switch it back), and the window is centred on the screen once. Nothing here requests focus, grabs the mouse or
     * moves the cursor (see BotMouseHelperMixin / BotMinecraftMixin). Safety net: if anything ever grabs the mouse anyway, it is released again within the
     * same tick and logged loudly.
     */
    private void keepUnfocusedFriendly() {
        mc.gameSettings.pauseOnLostFocus = false;
        if (Mouse.isGrabbed()) {
            Mouse.setGrabbed(false);
            if (mouseGrabWarnings++ < 20) {
                log("WARNING: the mouse was grabbed (no-grab mixins not effective for this path) - released again");
            }
        }
        if (!windowPlaced && ticks > 20) {
            windowPlaced = true;
            try {
                int[] desktop = NativeWindow.desktopMode();
                int dw = desktop[0];
                int dh = desktop[1];
                int x = Math.max(0, (dw - NativeWindow.width()) / 2);
                int y = Math.max(40, (dh - NativeWindow.height()) / 2 - 20);
                // no org.lwjgl.opengl in tooling (rule 3): the optional NativeWindow.setPosition(int,int) (contract request) is used when it exists
                try {
                    NativeWindow.class.getMethod("setPosition", int.class, int.class).invoke(null, x, y);
                } catch (NoSuchMethodException e) {
                    log("NativeWindow.setPosition not available: window left where the system put it");
                }
                log("window " + NativeWindow.width() + "x" + NativeWindow.height() + " placed at " + x + "," + y + " (desktop " + dw + "x" + dh + ")");
            } catch (Throwable t) {
                log("could not position the window: " + t);
            }
        }
    }

    /** Non-blocking window title update (tells the user who is watching which step is running). Safe to call from the script thread. */
    public void status(String text) {
        progress(text);
        long now = System.currentTimeMillis();
        if (now - lastStatusLogMs > 20_000) {
            lastStatusLogMs = now;
            log("progress: " + text);
        }
        report.writeThrottled(20_000);
        String title = "VK TestBot - " + text;
        clientTasks.add(new Task<>(() -> {
            try {
                NativeWindow.setTitle(title);
            } catch (Throwable ignored) {
            }
            return null;
        }));
    }

    /** Pacing for the user watching the run: waits the given number of client ticks unless -Dfml.vulkan.testbot.nopace=true. */
    public void pace(int tickCount) throws Exception {
        if (!Boolean.getBoolean("fml.vulkan.testbot.nopace") && tickCount > 0) {
            ticks(tickCount);
        }
    }

    private void drain(Queue<Task<?>> q) {
        Task<?> t;
        while ((t = q.poll()) != null) {
            t.execute();
        }
    }

    private void start() {
        thread = new Thread(() -> {
            try {
                new TestBotScript(this).run();
            } catch (Throwable t) {
                t.printStackTrace();
                report.setFatal("script thread died: " + t);
                report.write();
            } finally {
                finishAndExit();
            }
        }, "VkTestBot-script");
        thread.setDaemon(true);
        thread.start();
        startWatchdog();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Report.Section s = report.current();
            if (s != null) {
                report.setFatal("game exited while step '" + s.title + "' was running (crash or manual close)");
            }
            report.write();
            perf.close();
        }));
    }

    void finishAndExit() {
        report.write();
        perf.flush();
        log("finished, writing report to " + reportFile.getAbsolutePath() + " and exiting");
        try {
            clientCall(() -> {
                mc.shutdown();
                return null;
            }, 10_000);
        } catch (Throwable ignored) {
        }
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(25_000);
            } catch (InterruptedException ignored) {
            }
            report.write();
            perf.close();
            Runtime.getRuntime().halt(0);
        }, "VkTestBot-killer");
        killer.setDaemon(true);
        killer.start();
    }

    // ------------------------------------------------------------------ API for the script thread

    private static final class Task<T> {
        final Callable<T> body;
        volatile boolean done;
        T result;
        Throwable error;

        Task(Callable<T> body) {
            this.body = body;
        }

        void execute() {
            try {
                result = body.call();
            } catch (Throwable t) {
                error = t;
            } finally {
                done = true;
            }
        }
    }

    private <T> T await(Queue<Task<?>> q, Callable<T> body, long timeoutMs, String what) throws Exception {
        if (aborted) {
            throw new IllegalStateException("bot aborted");
        }
        Task<T> task = new Task<>(body);
        q.add(task);
        long end = System.currentTimeMillis() + timeoutMs;
        while (!task.done) {
            if (System.currentTimeMillis() > end) {
                throw new java.util.concurrent.TimeoutException(what + " task not executed within " + timeoutMs + " ms");
            }
            Thread.sleep(2);
        }
        lastProgressMs = System.currentTimeMillis(); // every completed bot interaction is progress (hang detection only cares about total silence)
        if (task.error != null) {
            if (task.error instanceof Exception e) {
                throw e;
            }
            throw new RuntimeException(task.error);
        }
        return task.result;
    }

    /** Runs on the client thread at the end of runTick. */
    public <T> T clientCall(Callable<T> c, long timeoutMs) throws Exception {
        return await(clientTasks, c, timeoutMs, "client");
    }

    public <T> T client(Callable<T> c) throws Exception {
        return clientCall(c, 60_000);
    }

    public void client(Runnable r) throws Exception {
        clientCall(() -> {
            r.run();
            return null;
        }, 60_000);
    }

    /** Runs on the client thread right after a frame was fully recorded (end of the game loop iteration). */
    public <T> T afterFrame(Callable<T> c) throws Exception {
        return await(renderTasks, c, 60_000, "post render");
    }

    /** Runs on the integrated server thread. Three consecutive timeouts abort the run (server thread dead). */
    public <T> T server(Callable<T> c) throws Exception {
        try {
            T r = await(serverTasks, c, 45_000, "server");
            consecutiveServerTimeouts = 0;
            return r;
        } catch (java.util.concurrent.TimeoutException e) {
            if (++consecutiveServerTimeouts >= 3) {
                aborted = true;
                report.setFatal("integrated server stopped responding (crashed?) - run aborted");
            }
            throw e;
        }
    }

    public void server(Runnable r) throws Exception {
        server(() -> {
            r.run();
            return null;
        });
    }

    public boolean isAborted() {
        return aborted;
    }

    public void abort(String why) {
        aborted = true;
        report.setFatal(why);
    }

    public long frameCount() {
        return frames;
    }

    public long tickCount() {
        return ticks;
    }

    public void frames(int n) throws Exception {
        waitCounter(0, n);
    }

    public void ticks(int n) throws Exception {
        waitCounter(1, n);
    }

    public void serverTicks(int n) throws Exception {
        waitCounter(2, n);
    }

    private long counter(int which) {
        return which == 0 ? frames : which == 1 ? ticks : serverTicks;
    }

    private void waitCounter(int which, int n) throws Exception {
        long target = counter(which) + n;
        long end = System.currentTimeMillis() + 60_000L + n * 500L;
        long lastBeat = 0;
        while (counter(which) < target) {
            if (aborted) {
                throw new IllegalStateException("bot aborted");
            }
            long now = System.currentTimeMillis();
            if (now > end) {
                throw new java.util.concurrent.TimeoutException("waited too long for " + n + " " + (which == 0 ? "frames" : which == 1 ? "ticks" : "server ticks"));
            }
            if (now - lastBeat > 5_000) {
                lastBeat = now;
                lastProgressMs = now; // a long wait that is still advancing is progress
            }
            Thread.sleep(2);
        }
    }

    /** Polls a client side condition (on the client thread) once per tick until true or the timeout (in ticks) runs out. */
    public boolean waitFor(Callable<Boolean> cond, int maxTicks) throws Exception {
        for (int i = 0; i < maxTicks; i++) {
            Boolean b = client(cond);
            if (b != null && b) {
                return true;
            }
            ticks(1);
        }
        return false;
    }

    /**
     * Saves the presented frame that shows the current state as {@code <captureDir>/<name>.png} (the offscreen colour image of the Vulkan backend, which is
     * everything the game drew; call from the script thread). MITE presents at the start of the next game loop iteration, so the frame that shows what a
     * step did is the second presented one (delay 1). Returns the path or null.
     */
    public String screenshot(Report.Section s, String name) {
        try {
            Path out = shots.resolve(name + ".png");
            Files.deleteIfExists(out);
            frames(2);
            RenderCore.requestCapture(name, 1);
            long end = System.currentTimeMillis() + 30_000;
            long lastSize = -1;
            int stable = 0;
            while (System.currentTimeMillis() < end) {
                if (Files.isRegularFile(out)) {
                    long size = Files.size(out);
                    if (size > 0 && size == lastSize) {
                        if (++stable >= 3) {
                            break;
                        }
                    } else {
                        stable = 0;
                    }
                    lastSize = size;
                }
                Thread.sleep(40);
            }
            if (stable < 3) {
                if (s != null) {
                    s.fail("screenshot " + name, "capture file did not appear within 30 s");
                }
                return null;
            }
            if (s != null) {
                s.screenshot(name + ".png");
            }
            progress("screenshot " + name);
            return out.toString();
        } catch (Exception e) {
            if (s != null) {
                s.exception("screenshot " + name, e);
            }
            return null;
        }
    }
}
