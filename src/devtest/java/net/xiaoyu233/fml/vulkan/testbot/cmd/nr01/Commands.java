package net.xiaoyu233.fml.vulkan.testbot.cmd.nr01;

import java.io.File;
import java.util.List;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/**
 * Devtest commands of work package NR-01 (GPU core): {@code arenaTest}, {@code readbackTest}, {@code gpuTimerTest}.
 *
 * <p>Standalone run (the bot core has no command runner of its own yet): with {@code -Dfml.vulkan.nr01.run=arenaTest,readbackTest,gpuTimerTest}
 * the provider starts a daemon thread when it is loaded; the thread waits for the first frames, runs the commands in order and writes the
 * markdown result to {@code <fml.vulkan.testbot.docs>/native/reports/NR-01-commands-<tag>.md} (default docs directory {@code ../docs}).
 */
public final class Commands implements DevCommandProvider {
    private static boolean started;

    public Commands() {
        String run = System.getProperty("fml.vulkan.nr01.run");
        if (run != null && !run.isBlank()) {
            startRunner(run.split(","));
        }
    }

    @Override
    public String packageId() {
        return "NR-01";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new ArenaTestCommand(), new ReadbackTestCommand(), new GpuTimerTestCommand());
    }

    private static synchronized void startRunner(String[] names) {
        if (started) {
            return;
        }
        started = true;
        Thread t = new Thread(() -> {
            try {
                while (!RenderCore.isInitialized() || RenderCore.frameNumber() < 120) {
                    Thread.sleep(50);
                }
                File docs = new File(System.getProperty("fml.vulkan.testbot.docs", "../docs"));
                String tag = System.getProperty("fml.vulkan.testbot.tag", "run");
                Report report = new Report(new File(docs, "native/reports/NR-01-commands-" + tag + ".md"), tag);
                for (String raw : names) {
                    String name = raw.trim();
                    DevCommand command = null;
                    for (DevCommand c : new Commands().commands()) {
                        if (c.name().equalsIgnoreCase(name)) {
                            command = c;
                        }
                    }
                    Report.Section s = report.begin(name, name);
                    try {
                        if (command == null) {
                            s.fail(name, "unknown command");
                        } else {
                            command.run(s, new String[0]);
                        }
                    } catch (Throwable e) {
                        s.exception(name, e);
                    }
                    report.end(s);
                }
                System.out.println("[VkTestBot] NR-01 commands finished: " + new File(docs, "native/reports/NR-01-commands-" + tag + ".md"));
            } catch (InterruptedException ignored) {
            }
        }, "NR-01-commands");
        t.setDaemon(true);
        t.start();
    }
}
