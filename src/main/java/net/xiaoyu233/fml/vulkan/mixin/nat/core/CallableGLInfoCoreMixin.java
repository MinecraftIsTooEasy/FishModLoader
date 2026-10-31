package net.xiaoyu233.fml.vulkan.mixin.nat.core;

import net.xiaoyu233.fml.vulkan.render.state.GlStrings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Native area "core": the crash report / debug line names the Vulkan device instead of asking GL (D7). */
@Mixin(targets = "net.minecraft.CallableGLInfo")
public abstract class CallableGLInfoCoreMixin {
    /**
     * {@code glGetString(GL_RENDERER) + " GL version " + glGetString(GL_VERSION) + ", " + glGetString(GL_VENDOR)} from the device info.
     *
     * @author fml-native
     * @reason native renderer: no GL strings, the selected Vulkan device is reported
     */
    @Overwrite
    public String getTexturePack() {
        return GlStrings.renderer() + " GL version " + GlStrings.version() + ", " + GlStrings.vendor();
    }
}
