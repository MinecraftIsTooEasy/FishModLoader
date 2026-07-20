package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.audio.SoundManager;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IMixinSoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SoundManager.class)
public class SoundManagerMixin implements IMixinSoundManager {
    @Unique
    private boolean forge_LOAD_SOUND_SYSTEM;

    @Override
    public boolean isSoundSystemLoaded() {
        return this.forge_LOAD_SOUND_SYSTEM;
    }

    @Override
    public void setSoundSystemLoaded(boolean loaded) {
        this.forge_LOAD_SOUND_SYSTEM = loaded;
    }
}
