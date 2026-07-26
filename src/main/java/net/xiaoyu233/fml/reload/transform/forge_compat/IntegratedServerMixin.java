package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.logging.ILogAgent;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.world.WorldSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {

    @Shadow
    private net.minecraft.world.WorldSettings theWorldSettings;

    @Shadow
    public Profiler theProfiler;

    @Shadow
    public abstract ILogAgent getLogAgent();

    @Shadow
    public abstract net.minecraft.server.management.ServerConfigurationManager getConfigurationManager();

    @Shadow
    public abstract boolean isSinglePlayer();

    @Shadow
    public abstract void setDifficultyForAllWorlds(int par1);

    @Shadow
    public abstract void initialWorldChunkLoad();
}
