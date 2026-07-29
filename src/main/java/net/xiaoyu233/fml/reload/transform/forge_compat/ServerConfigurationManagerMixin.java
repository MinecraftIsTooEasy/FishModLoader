package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(ServerConfigurationManager.class)
public abstract class ServerConfigurationManagerMixin {

    @Shadow
    @Final
    private MinecraftServer mcServer;

    @Shadow
    @Final
    private List playerEntityList;
}
