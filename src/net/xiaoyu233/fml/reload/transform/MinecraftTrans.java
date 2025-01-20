package net.xiaoyu233.fml.reload.transform;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.Minecraft;
import net.xiaoyu233.fml.FishModLoader;
import net.xiaoyu233.fml.config.Configs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
public class MinecraftTrans {
    @Shadow
    private static String error_message;

    @Overwrite
    public static void setErrorMessage(String text, boolean echo_to_err) {
        FishModLoader.LOGGER.error(text);
        if (Configs.Debug.DEBUG.get()){
            if (echo_to_err && (error_message == null || !error_message.equals(text))) {
                System.err.println(text);
            }
            if (error_message == null) {
                error_message = text.replaceAll("\n", "");
            }
        }
    }

    @ModifyReturnValue(method = "inDevMode", at = @At("TAIL"))
    private static boolean inDevMode(boolean original) {
        if (FishModLoader.isDevelopmentEnvironment() && Configs.Debug.DEVENV.get())
            return true;
        return original;
    }
}
