package net.xiaoyu233.fml.reload.transform.entrypoint;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.minecraft.client.main.Main;
import net.xiaoyu233.fml.FishModLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Main.class)
public class ClientEntrypointMixin {
    @Inject(method = "main([Ljava/lang/String;)V", at = @At(value = "NEW", target = "(Lnet/minecraft/util/Session;IIZZLjava/io/File;Ljava/io/File;Ljava/io/File;Ljava/net/Proxy;Ljava/lang/String;)Lnet/minecraft/client/Minecraft;", shift = At.Shift.BEFORE))
    private static void injectMain(CallbackInfo callbackInfo){
        // Forge mod PreInit fires before Minecraft is instantiated, matching
        // upstream Forge 1.6.4 where PreInit runs early during FMLLoadingPlugin
        // loadModContainer.
        FishModLoader.fireForgePreInit();

        FishModLoader.invokeEntrypoints("main", ModInitializer.class, modInitializer -> {
            modInitializer.createConfig().ifPresent(configRegistry -> {
                FishModLoader.addConfigRegistry(configRegistry);
                configRegistry.reloadConfig();
            });
            modInitializer.onInitialize();
        });
        FishModLoader.invokeEntrypoints("client", ClientModInitializer.class, ClientModInitializer::onInitializeClient);

        // Init / PostInit follow once Fabric entrypoints are done. Forge's
        // contract: every mod is constructed before any of them receives
        // PostInit.
        FishModLoader.fireForgeInit();
        FishModLoader.fireForgePostInit();
    }
}
