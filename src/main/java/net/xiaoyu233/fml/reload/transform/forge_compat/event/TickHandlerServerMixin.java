package net.xiaoyu233.fml.reload.transform.forge_compat.event;

import cpw.mods.fml.common.TickRegistry;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumSet;

@Mixin(MinecraftServer.class)
public abstract class TickHandlerServerMixin {
    @Shadow public WorldServer[] worldServers;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void fmlForgeServerTickStart(CallbackInfo callbackInfo) {
        EnumSet<TickType> types = EnumSet.of(TickType.SERVER);
        for (WorldServer w : this.worldServers) {
            if (w != null) {
                types.add(TickType.WORLD);
                break;
            }
        }
        TickRegistry.dispatchStart(Side.SERVER, types);
    }

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void fmlForgeServerTickEnd(CallbackInfo callbackInfo) {
        EnumSet<TickType> types = EnumSet.of(TickType.SERVER);
        for (WorldServer w : this.worldServers) {
            if (w != null) {
                types.add(TickType.WORLD);
                break;
            }
        }
        TickRegistry.dispatchEnd(Side.SERVER, types);
    }
}
