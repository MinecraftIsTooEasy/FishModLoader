package net.xiaoyu233.fml.forge.event;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.xiaoyu233.fml.FishModLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Translates Forge 1.6.4 MinecraftServer event triggers into MITE hooks.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerEventsMixin {

    @Shadow public WorldServer[] worldServers;
    @Unique private boolean fmlForgeServerAboutToStart;
    @Unique private boolean fmlForgeServerStarted;
    @Unique private boolean fmlForgeServerStopping;

    @Inject(method = "loadAllWorlds(Ljava/lang/String;Ljava/lang/String;JLnet/minecraft/world/WorldType;Ljava/lang/String;)V",
            at = @At("HEAD"))
    private void fmlForgeOnServerAboutToStart(String saveName, String levelName, long seed,
                                              WorldType worldType, String generatorOptions,
                                              CallbackInfo callbackInfo) {
        if (fmlForgeServerAboutToStart) return;
        fmlForgeServerAboutToStart = true;

        MinecraftServer server = (MinecraftServer) (Object) this;
        FishModLoader.fireForgeServerAboutToStart(server);
    }

    @Inject(method = "loadAllWorlds(Ljava/lang/String;Ljava/lang/String;JLnet/minecraft/world/WorldType;Ljava/lang/String;)V",
            at = @At("RETURN"))
    private void fmlForgeOnAllWorldsLoaded(String saveName, String levelName, long seed,
                                           WorldType worldType, String generatorOptions,
                                           CallbackInfo callbackInfo) {
        if (worldServers != null) {
            for (WorldServer world : worldServers) {
                if (world != null) {
                    MinecraftForge.EVENT_BUS.post(new WorldEvent.Load(world));
                }
            }
        }

        if (!fmlForgeServerStarted) {
            fmlForgeServerStarted = true;
            MinecraftServer server = (MinecraftServer) (Object) this;
            FishModLoader.fireForgeServerStarting(server);
            FishModLoader.fireForgeServerStarted();
        }
    }

    @Inject(method = "stopServer()V", at = @At("HEAD"))
    private void fmlForgeOnStopServerHead(CallbackInfo callbackInfo) {
        if (fmlForgeServerStarted && !fmlForgeServerStopping) {
            fmlForgeServerStopping = true;
            FishModLoader.fireForgeServerStopping();
        }

        if (worldServers == null) return;
        for (WorldServer world : worldServers) {
            if (world != null) {
                MinecraftForge.EVENT_BUS.post(new WorldEvent.Unload(world));
            }
        }
    }

    @Inject(method = "stopServer()V", at = @At("RETURN"))
    private void fmlForgeOnStopServerReturn(CallbackInfo callbackInfo) {
        if (fmlForgeServerStarted) {
            FishModLoader.fireForgeServerStopped();
            fmlForgeServerStarted = false;
            fmlForgeServerAboutToStart = false;
            fmlForgeServerStopping = false;
        }
    }
}
