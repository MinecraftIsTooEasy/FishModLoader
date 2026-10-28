package net.xiaoyu233.fml.vulkan.mixin.nat.texture;

import net.minecraft.Minecraft;
import net.xiaoyu233.fml.vulkan.render.texture.Textures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Native area "texture": the size probe of the texture system (1.11). */
@Mixin(Minecraft.class)
public abstract class MinecraftTextureMixin {
    /**
     * MITE probes downward from 16384 with proxy textures; the device limit answers the same question.
     *
     * @author fml-native
     * @reason native renderer: {@code min(maxImageDimension2D, 16384)} from the device instead of GL proxy texture probes
     */
    @Overwrite
    public static int getGLMaximumTextureSize() {
        return Textures.maxSize();
    }
}
