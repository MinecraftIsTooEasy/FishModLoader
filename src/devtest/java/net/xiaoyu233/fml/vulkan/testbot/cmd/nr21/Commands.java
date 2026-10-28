package net.xiaoyu233.fml.vulkan.testbot.cmd.nr21;

import java.util.List;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/** Devtest commands of work package NR-21 (owner: NR-21; created empty by NR-00). */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-21";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of();
    }
}
