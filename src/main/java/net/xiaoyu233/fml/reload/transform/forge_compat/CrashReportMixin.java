package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.crash.CrashReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrashReport.class)
public class CrashReportMixin {

    @Inject(method = "getSuspiciousClasses", at = @At("HEAD"))
    private void fmlForgeGetSuspiciousClasses(CallbackInfo ci) {
        // The actual fix is in CallableSuspiciousClasses
    }

    @Inject(method = "populateEnvironment", at = @At("RETURN"))
    private void fmlForgePopulateEnvironment(CallbackInfo ci) {
        // Additional crash information hook
    }
}
