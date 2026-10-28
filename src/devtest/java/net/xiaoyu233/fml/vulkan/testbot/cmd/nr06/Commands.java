package net.xiaoyu233.fml.vulkan.testbot.cmd.nr06;

import java.util.List;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/** Devtest commands of work package NR-06 (owner: NR-06): {@code mathTest}. */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-06";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new MathTestCommand());
    }
}
