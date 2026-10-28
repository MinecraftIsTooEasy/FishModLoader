package net.xiaoyu233.fml.vulkan.testbot.cmd.nr12;

import java.util.List;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/** Devtest commands of work package NR-12 (owner: NR-12): {@code frustumTest}, {@code shadowCheck}, {@code worldFrameCapture}. */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-12";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new FrustumTestCommand(), new ShadowCheckCommand(), new WorldFrameCaptureCommand());
    }
}
