package net.xiaoyu233.fml.vulkan.testbot.cmd.nr03;

import java.util.List;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/** Devtest commands of work package NR-03 (owner: NR-03; created empty by NR-00). */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-03";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of();
    }
}
