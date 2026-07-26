package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.LoadingScreenRenderer;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.StringTranslate;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IMixinMinecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin implements IMixinMinecraft {
    @Shadow private boolean integratedServerIsRunning;
    @Shadow private LoadingScreenRenderer loadingScreen;
    @Shadow private GuiScreen currentScreen;
    @Shadow public WorldClient theWorld;

    @Override
    public void continueWorldLoading() {
        this.integratedServerIsRunning = true;
        // MITE renamed LoadingScreenRenderer.displayProgressMessage()
        // to resetProgressAndMessage()
        this.loadingScreen.resetProgressAndMessage(StringTranslate.getInstance().translateKey("menu.loadingLevel"));
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fmlForgeOnStartGame(CallbackInfo ci) {
        // Forge post-startup events would fire here.
    }

    @Inject(method = "displayGuiScreen", at = @At("HEAD"), cancellable = true)
    private void fmlForgeOnDisplayGuiScreen(GuiScreen par1GuiScreen, CallbackInfo ci) {
        GuiScreen old = this.currentScreen;
        GuiOpenEvent event = new GuiOpenEvent(par1GuiScreen);
        if (MinecraftForge.EVENT_BUS.post(event)) {
            ci.cancel();
            return;
        }
        if (event.gui != par1GuiScreen) {
            if (old != null && old != event.gui) {
                old.onGuiClosed();
            }
        }
    }

    @Inject(method = "loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V",
            at = @At("HEAD"))
    private void fmlForgeOnLoadWorld(WorldClient par1WorldClient, String par2Str, CallbackInfo ci) {
        if (this.theWorld != null) {
            MinecraftForge.EVENT_BUS.post(new WorldEvent.Unload(this.theWorld));
        }
    }

    @Inject(method = "shutdown", at = @At("HEAD"))
    private void fmlForgeOnShutdown(CallbackInfo ci) {
        if (this.theWorld != null) {
            MinecraftForge.EVENT_BUS.post(new WorldEvent.Unload(this.theWorld));
        }
    }

    @Unique
    private boolean fmlForgeHandleClientCommand() {
        throw new UnsupportedOperationException(
                "Body replacement required. See patches for handleClientCommand.");
    }

    @Unique
    private static int fmlForgeGetGLMaximumTextureSize() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for getGLMaximumTextureSize cache.");
    }
}
