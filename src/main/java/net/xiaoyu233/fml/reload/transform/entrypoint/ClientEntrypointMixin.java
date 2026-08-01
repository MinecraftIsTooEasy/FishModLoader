package net.xiaoyu233.fml.reload.transform.entrypoint;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.minecraft.client.Minecraft;
import net.xiaoyu233.fml.FishModLoader;
import net.xiaoyu233.fml.modfixer.LegacyModLifecycle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.client.resources.ReloadableResourceManager;
import net.minecraft.client.resources.ResourcePack;
import java.util.List;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class ClientEntrypointMixin {
    @Shadow private ReloadableResourceManager mcResourceManager;
    @Shadow private List<ResourcePack> defaultResourcePacks;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void injectMain(CallbackInfo callbackInfo){
        LegacyModLifecycle.prepareClientResources(this, this.defaultResourcePacks, this.mcResourceManager);

        // MITE initializes its vanilla item/block/stat registries while the
        // Minecraft client is being constructed.  IngameIME's preInit loads
        // Forge Configuration, which in turn initializes Item and StatList;
        // firing it before this constructor would therefore initialize
        // AchievementList while its referenced items are still null.
        // Run the client lifecycle after the constructor, once those registries
        // are ready (the client is still before the main menu at this point).
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
