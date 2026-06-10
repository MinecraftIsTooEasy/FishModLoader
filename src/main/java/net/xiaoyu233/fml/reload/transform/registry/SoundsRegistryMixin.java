package net.xiaoyu233.fml.reload.transform.registry;

import net.minecraft.client.audio.SoundManager;
import net.minecraft.client.resources.ResourceManager;
import net.minecraft.client.settings.GameSettings;
import net.xiaoyu233.fml.reload.event.MITEEvents;
import net.xiaoyu233.fml.reload.event.SoundsRegisterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

@Mixin(SoundManager.class)
public abstract class SoundsRegistryMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onSoundRegistry(ResourceManager par1ResourceManager, GameSettings par2GameSettings, File par3File, CallbackInfo ci) {
        MITEEvents.MITE_EVENT_BUS.post(new SoundsRegisterEvent((SoundManager) (Object) this));
    }
}
