package net.xiaoyu233.fml.vulkan.testbot.cmd.nr16;

import java.util.List;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/** Devtest commands of work package NR-16 (owner: NR-16): {@code textCapture}, {@code cursorCommand}. */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-16";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new TextCaptureCommand(), new CursorCommand());
    }
}
