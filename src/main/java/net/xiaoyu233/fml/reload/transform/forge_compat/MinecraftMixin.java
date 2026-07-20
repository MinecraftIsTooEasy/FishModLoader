package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.LoadingScreenRenderer;
import net.minecraft.util.StringTranslate;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IMixinMinecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Minecraft.class)
public class MinecraftMixin implements IMixinMinecraft {
    @Shadow private boolean integratedServerIsRunning;
    @Shadow private LoadingScreenRenderer loadingScreen;

    @Override
    public void continueWorldLoading() {
        this.integratedServerIsRunning = true;
        this.loadingScreen.displayProgressMessage(StringTranslate.getInstance().translateKey("menu.loadingLevel"));
    }
}
