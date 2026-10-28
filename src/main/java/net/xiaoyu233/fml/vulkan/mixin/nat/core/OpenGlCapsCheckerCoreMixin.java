package net.xiaoyu233.fml.vulkan.mixin.nat.core;

import net.minecraft.OpenGlCapsChecker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Native area "core": occlusion queries do not exist on this backend (D5), so the capability probe never touches a GL context. */
@Mixin(OpenGlCapsChecker.class)
public abstract class OpenGlCapsCheckerCoreMixin {
    /**
     * @author fml-native
     * @reason native renderer: the occlusion-query paths are removed, so the capability is reported as absent
     */
    @Overwrite
    public static boolean checkARBOcclusion() {
        return false;
    }
}
