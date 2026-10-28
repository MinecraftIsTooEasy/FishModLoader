package net.xiaoyu233.fml.vulkan.testbot.cmd;

import java.util.List;

/**
 * The commands of one work package. Each package NN owns {@code net.xiaoyu233.fml.vulkan.testbot.cmd.nrNN.Commands}, a public class with
 * a public no-argument constructor that implements this interface; {@link DevCommands} finds it by class name, no shared list exists.
 */
public interface DevCommandProvider {
    /** The package id, e.g. {@code "NR-06"}. */
    String packageId();

    /** The commands; empty for a package without devtest commands. */
    List<DevCommand> commands();
}
