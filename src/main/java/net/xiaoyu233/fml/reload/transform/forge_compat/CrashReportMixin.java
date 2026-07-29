package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.crash.CrashReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrashReport.class)
public class CrashReportMixin {

    /**
     * @reason Stop MITE's crash reporter from masking real errors.
     *
     * <p>{@code func_85057_a(String, int)} computes
     * {@code throwable.getStackTrace().length - depth} and uses the result as an
     * array index. Mixin adds handler frames to the live thread stack, so the
     * depth reported by CrashReportCategory can exceed the throwable's own
     * (shorter) trace. The subtraction then goes negative and the crash reporter
     * throws "Index -1 out of bounds" from inside itself, replacing whatever
     * error was originally being reported -- which makes any such failure
     * effectively undebuggable.
     *
     * <p>Clamping the requested depth keeps the index in range so the genuine
     * cause is reported.
     */
    @ModifyVariable(method = "makeCategoryDepth(Ljava/lang/String;I)Lnet/minecraft/crash/CrashReportCategory;",
                    at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private int fmlClampStackTraceDepth(int depth) {
        Throwable cause = ((CrashReport) (Object) this).getCrashCause();
        if (cause == null) return depth;
        StackTraceElement[] trace = cause.getStackTrace();
        if (trace == null) return depth;
        return Math.max(0, Math.min(depth, trace.length));
    }


    @Inject(method = "populateEnvironment", at = @At("RETURN"))
    private void fmlForgePopulateEnvironment(CallbackInfo ci) {
        // Additional crash information hook
    }
}
