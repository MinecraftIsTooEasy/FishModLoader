package net.xiaoyu233.fml.vulkan.testbot.cmd.nr20;

import java.util.List;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/** Devtest commands of work package NR-20: {@code specialScene} (frozen special-renderer scenes) and {@code specialClear}. */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-20";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new SpecialSceneCommand(), new SpecialSceneCommand.Clear());
    }
}
