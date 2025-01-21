package net.xiaoyu233.fml.reload.event;

import net.minecraft.ResourceLocation;
import net.minecraft.SoundManager;

public class SoundsRegisterEvent {
    private final SoundManager soundManager;

    public SoundsRegisterEvent(SoundManager soundManager) {
        this.soundManager = soundManager;
    }

    @Deprecated(since = "3.4.1")
    public void register(String path) {
        registerSound(new ResourceLocation(path));
    }

    public void registerSound(ResourceLocation identifier) {
        registerSound(identifier, 1);
    }

    public void registerSound(ResourceLocation identifier, int variantCount) {
        String string = identifier.toString();
        if (variantCount > 1) {
            for (int i = 0; i < variantCount; i++) {
                String path = string.replace('.', '/');
                soundManager.addSound(path + (i + 1) + ".ogg");
            }
        } else {
            String path = string.replace('.', '/');
            soundManager.addSound(path + ".ogg");
        }
    }

    public void registerStreaming(ResourceLocation identifier) {
        String path = identifier.toString();
        path = path.replace(".", "/");
        soundManager.addStreaming(path + ".ogg");
    }

    /**
     * Needs verifying.
     */
    public void registerMusic(ResourceLocation identifier) {
        String path = identifier.toString();
        path = path.replace(".", "/");
        soundManager.addMusic(path + ".ogg");
    }

}
