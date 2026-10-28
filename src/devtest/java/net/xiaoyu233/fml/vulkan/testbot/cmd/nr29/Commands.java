package net.xiaoyu233.fml.vulkan.testbot.cmd.nr29;

import java.util.List;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/** Devtest commands of work package NR-29 (owner: NR-29; created empty by NR-00). */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-29";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of();
    }
}
