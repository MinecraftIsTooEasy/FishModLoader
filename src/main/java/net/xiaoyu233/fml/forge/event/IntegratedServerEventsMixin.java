package net.xiaoyu233.fml.forge.event;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.xiaoyu233.fml.FishModLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * IntegratedServer overrides the world-load path used by dedicated servers.
 * Forge mods still expect the normal server lifecycle there, especially for
 * registering commands through FMLServerStartedEvent.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerEventsMixin {
    @Unique private boolean fmlForgeServerAboutToStart;
    @Unique private boolean fmlForgeServerStarted;

    @Inject(method = "loadAllWorlds(Ljava/lang/String;Ljava/lang/String;JLnet/minecraft/world/WorldType;Ljava/lang/String;)V",
            at = @At("HEAD"))
    private void fmlForgeOnIntegratedServerAboutToStart(String saveName, String levelName, long seed,
                                                        WorldType worldType, String generatorOptions,
                                                        CallbackInfo callbackInfo) {
        if (fmlForgeServerAboutToStart) return;
        fmlForgeServerAboutToStart = true;

        MinecraftServer server = (MinecraftServer) (Object) this;
        FishModLoader.fireForgeServerAboutToStart(server);
    }

    @Inject(method = "loadAllWorlds(Ljava/lang/String;Ljava/lang/String;JLnet/minecraft/world/WorldType;Ljava/lang/String;)V",
            at = @At("RETURN"))
    private void fmlForgeOnIntegratedWorldsLoaded(String saveName, String levelName, long seed,
                                                  WorldType worldType, String generatorOptions,
                                                  CallbackInfo callbackInfo) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        if (server.worldServers != null) {
            for (WorldServer world : server.worldServers) {
                if (world != null) {
                    MinecraftForge.EVENT_BUS.post(new WorldEvent.Load(world));
                }
            }
        }

        if (!fmlForgeServerStarted) {
            fmlForgeServerStarted = true;
            FishModLoader.fireForgeServerStarting(server);
            FishModLoader.fireForgeServerStarted();
        }
    }
}
