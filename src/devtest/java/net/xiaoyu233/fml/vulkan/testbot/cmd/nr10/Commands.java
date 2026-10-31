package net.xiaoyu233.fml.vulkan.testbot.cmd.nr10;

import java.util.List;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/** Devtest commands of work package NR-10: off-thread meshing ({@code meshCompare}, {@code snapCompare}, {@code stress}, {@code workerStress}, {@code flowerPot}, {@code visTest}). */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-10";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new MeshCompareCommand(), new SnapCompareCommand(), new StressCommand(false), new StressCommand(true), new FlowerPotCommand(),
                new VisTestCommand());
    }
}
