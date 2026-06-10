package net.xiaoyu233.fml.reload.transform.entrypoint;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.minecraft.server.MinecraftServer;
import net.xiaoyu233.fml.FishModLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class ServerEntrypointMixin {
    @Inject(method = "main", at = @At(value = "INVOKE", target = "Lnet/minecraft/stats/StatList;nopInit()V", shift = At.Shift.BEFORE), require = 1)
    private static void injectMain(CallbackInfo callbackInfo){
        // Forge PreInit fires before any other mod-loaded code runs, matching
        // dedicated-server semantics in Forge 1.6.4.
        FishModLoader.fireForgePreInit();

        FishModLoader.invokeEntrypoints("main", ModInitializer.class, modInitializer -> {
            modInitializer.createConfig().ifPresent(configRegistry -> {
                FishModLoader.addConfigRegistry(configRegistry);
                configRegistry.reloadConfig();
            });
            modInitializer.onInitialize();
        });
        FishModLoader.invokeEntrypoints("server", DedicatedServerModInitializer.class, DedicatedServerModInitializer::onInitializeServer);

        // After Fabric entrypoints, run the classic Init / PostInit pair.
        // ServerStarting / ServerStarted will be fired separately by hooks
        // attached to MinecraftServer.startServer() in stage 5.
        FishModLoader.fireForgeInit();
        FishModLoader.fireForgePostInit();
    }
}
