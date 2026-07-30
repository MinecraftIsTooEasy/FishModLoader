package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.logging.ILogAgent;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Hashtable;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Shadow
    public WorldServer[] worldServers;

    @Shadow
    private ServerConfigurationManager serverConfigManager;

    @Shadow
    public abstract boolean isSinglePlayer();

    @Shadow
    public abstract boolean isDemo();

    @Shadow
    @Final
    public Profiler theProfiler;

    @Shadow
    public ILogAgent getLogAgent() { return null; }

    @Shadow
    public abstract boolean getAllowNether();

    @Shadow
    public abstract void initialWorldChunkLoad();

    @Shadow
    public abstract void setDifficultyForAllWorlds(int par1);

    @Shadow
    public abstract int getDifficulty();

    @Shadow
    public abstract ServerConfigurationManager getConfigurationManager();

    @Shadow
    private int tickCounter;

    @Unique
    public Hashtable<Integer, long[]> worldTickTimes = new Hashtable<Integer, long[]>();

    @Inject(method = "tick", at = @At("HEAD"))
    private void fmlForgeTickPre(CallbackInfo ci) {
        if (worldServers == null) return;
    }
}
