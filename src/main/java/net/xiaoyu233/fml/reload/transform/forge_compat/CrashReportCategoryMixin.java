package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.crash.CrashReportCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CrashReportCategory.class)
public class CrashReportCategoryMixin {

    @Shadow
    private StackTraceElement[] stackTrace;

    @Inject(method = "func_85073_a", at = @At("HEAD"), cancellable = true)
    private void fmlForgeFixAIOOB(int par1, CallbackInfoReturnable<Integer> cir) {
        StackTraceElement[] astacktraceelement = Thread.currentThread().getStackTrace();
        int len = astacktraceelement.length - 3 - par1;
        if (len <= 0) len = astacktraceelement.length;
        this.stackTrace = new StackTraceElement[len];
        System.arraycopy(astacktraceelement, astacktraceelement.length - len, this.stackTrace, 0, this.stackTrace.length);
        cir.setReturnValue(this.stackTrace.length);
    }
}
