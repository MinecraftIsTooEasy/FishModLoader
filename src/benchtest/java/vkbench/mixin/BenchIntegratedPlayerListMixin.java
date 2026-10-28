package vkbench.mixin;

import net.minecraft.IntegratedPlayerList;
import net.minecraft.ServerConfigurationManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev only: MITE's integrated server hard codes 10 chunks of view distance; {@code -Dfml.bench.viewDistance=16} raises it so the Far pass really loads 16 chunks. */
@Mixin(IntegratedPlayerList.class)
public abstract class BenchIntegratedPlayerListMixin extends ServerConfigurationManager {
    protected BenchIntegratedPlayerListMixin() {
        super(null);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vkbench$viewDistance(CallbackInfo ci) {
        int v = Integer.getInteger("fml.bench.viewDistance", 0);
        if (v > 0) {
            this.viewDistance = v;
        }
    }
}
