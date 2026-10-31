package net.xiaoyu233.fml.vulkan.testbot.cmd;

import net.xiaoyu233.fml.vulkan.testbot.Report;

/**
 * One devtest command of a work package (for example {@code mathTest} of NR-06): a named check that runs on the bot thread and reports
 * into a {@link Report.Section}. Commands are provided by {@link DevCommandProvider}s and looked up by {@link DevCommands}.
 */
public interface DevCommand {
    /** Unique, case-insensitive command name; by convention the package prefix is not part of it (e.g. {@code arenaTest}). */
    String name();

    /** One line for the command listing. */
    String description();

    /**
     * Runs the command on the bot thread (never on the render thread; commands that need the render thread hand work over through
     * {@code TestBot}). Failures are reported through {@code section}; an exception is recorded by the caller as a failed command.
     *
     * @param args the whitespace separated arguments after the command name, never null
     */
    void run(Report.Section section, String[] args) throws Exception;
}
