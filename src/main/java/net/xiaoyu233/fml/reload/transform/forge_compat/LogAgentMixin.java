package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.logging.LogAgent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LogAgent.class)
public class LogAgentMixin {

    @Inject(method = "logWarningFormatted", at = @At("HEAD"), cancellable = true)
    private void fmlForgeLogWarningFormatted(String par1Str, Object[] par2ArrayOfObj, CallbackInfo ci) {
        ci.cancel();
        String.format(par1Str, par2ArrayOfObj);
    }
}
