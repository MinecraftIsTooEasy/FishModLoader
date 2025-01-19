package net.xiaoyu233.fml.reload.transform.fix;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.WorldMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WorldMap.class)
public class FixVoid {
    @ModifyExpressionValue(method = "addSurvey", at = @At(value = "INVOKE", target = "Lnet/minecraft/Chunk;getHeightValue(II)I"))
    private int doNotCrash(int original) {
        return original + 1;
    }
}
