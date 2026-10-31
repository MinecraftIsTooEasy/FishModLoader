package net.xiaoyu233.fml.vulkan.testbot.cmd.nr09;

import java.util.List;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/** Devtest commands of work package NR-09 (owner: NR-09): {@code nr09.terrainStats}, {@code nr09.translucentOrder}. */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-09";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new TerrainStatsCommand(), new TranslucentOrderCommand());
    }
}
