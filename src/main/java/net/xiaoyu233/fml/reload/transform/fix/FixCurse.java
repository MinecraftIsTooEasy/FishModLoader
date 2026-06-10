package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.util.Curse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Curse.class)
public class FixCurse {
    @Redirect(method = "getRandomCurse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;inDevMode()Z"))
    private static boolean redirect() {
        return false;
    }
}
