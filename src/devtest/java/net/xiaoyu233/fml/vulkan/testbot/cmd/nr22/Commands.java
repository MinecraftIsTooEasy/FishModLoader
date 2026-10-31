package net.xiaoyu233.fml.vulkan.testbot.cmd.nr22;

import java.util.List;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/** Devtest commands of work package NR-22 (owner: NR-22; created empty by NR-00). */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-22";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of();
    }
}
