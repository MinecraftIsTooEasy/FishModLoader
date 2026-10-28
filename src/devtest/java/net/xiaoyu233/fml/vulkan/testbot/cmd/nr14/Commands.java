package net.xiaoyu233.fml.vulkan.testbot.cmd.nr14;

import java.util.List;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/** Devtest commands of work package NR-14: {@code itemCaptures}, {@code mapInline}, {@code handSweep}. */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-14";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new ItemCapturesCommand(), new MapInlineCommand(), new HandSweepCommand());
    }
}
