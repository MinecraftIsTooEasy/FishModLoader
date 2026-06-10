package net.xiaoyu233.fml.forge.event;

import cpw.mods.fml.common.TickRegistry;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumSet;

/**
 * Drives {@link TickRegistry} dispatch on the server side.
 *
 * <p>{@link MinecraftServer#tick()} is the integrated/dedicated server's
 * heartbeat (50 ms cadence). We fire SERVER tick around the whole method,
 * and WORLD ticks for every loaded WorldServer the server tells about.
 *
 * <p>Forge 1.6.4 also delivers PLAYER ticks server-side per online player;
 * MITE doesn't make that loop trivially observable so it's omitted here —
 * mods that need per-player server ticks can iterate
 * {@code server.getConfigurationManager().playerEntityList} themselves
 * inside their SERVER handler.
 */
@Mixin(MinecraftServer.class)
public abstract class TickHandlerServerMixin {

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void fmlForgeServerTickStart(CallbackInfo callbackInfo) {
        MinecraftServer self = (MinecraftServer) (Object) this;
        EnumSet<TickType> types = EnumSet.of(TickType.SERVER);
        if (self.worldServers != null) {
            for (WorldServer w : self.worldServers) {
                if (w != null) { types.add(TickType.WORLD); break; }
            }
        }
        TickRegistry.dispatchStart(Side.SERVER, types);
    }

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void fmlForgeServerTickEnd(CallbackInfo callbackInfo) {
        MinecraftServer self = (MinecraftServer) (Object) this;
        EnumSet<TickType> types = EnumSet.of(TickType.SERVER);
        if (self.worldServers != null) {
            for (WorldServer w : self.worldServers) {
                if (w != null) { types.add(TickType.WORLD); break; }
            }
        }
        TickRegistry.dispatchEnd(Side.SERVER, types);
    }
}
