package vkbench.mixin;

import net.minecraft.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev only: logs every sound the game asks for (name, volume, pitch) with the nanoTime, for the placement probe. */
@Mixin(SoundManager.class)
public abstract class BenchSoundMixin {
    @Inject(method = "playSound", at = @At("HEAD"))
    private void vkbench$s(String n, float x, float y, float z, float v, float p, CallbackInfo ci) {
        if (Boolean.getBoolean("fml.vulkan.placeShots")) {
            System.out.println("[VkBench] SND " + System.nanoTime() / 1000 + " " + n + " v=" + v + " p=" + p);
        }
    }

    @Inject(method = "playSoundFX", at = @At("HEAD"))
    private void vkbench$f(String n, float v, float p, CallbackInfo ci) {
        if (Boolean.getBoolean("fml.vulkan.placeShots")) {
            System.out.println("[VkBench] SNDFX " + System.nanoTime() / 1000 + " " + n + " v=" + v + " p=" + p);
        }
    }
}
