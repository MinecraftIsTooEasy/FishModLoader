package net.xiaoyu233.fml.vulkan.testbot.cmd.nr04;

import java.util.List;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/** Devtest commands of work package NR-04: texture system ({@code mapUploadTest}, {@code atlasDump}, {@code textureUnit}). */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-04";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new MapUploadTestCommand(), new AtlasDumpCommand(), new TextureUnitCommand());
    }
}
