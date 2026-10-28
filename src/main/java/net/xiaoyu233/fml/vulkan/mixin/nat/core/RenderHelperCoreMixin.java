package net.xiaoyu233.fml.vulkan.mixin.nat.core;

import net.minecraft.RenderHelper;
import net.xiaoyu233.fml.vulkan.render.state.Lighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Native area "core": {@code RenderHelper} is the native {@link Lighting} (1.10): CPU lighting per vertex instead of GL lights. */
@Mixin(RenderHelper.class)
public abstract class RenderHelperCoreMixin {
    /**
     * @author fml-native
     * @reason native renderer: lighting state lives in Draw/Lighting
     */
    @Overwrite
    public static void disableStandardItemLighting() {
        Lighting.disable();
    }

    /**
     * @author fml-native
     * @reason native renderer: two directional lights evaluated on the CPU (eye-space directions captured from the current pose)
     */
    @Overwrite
    public static void enableStandardItemLighting() {
        Lighting.enableStandard();
    }

    /**
     * @author fml-native
     * @reason native renderer: push, rotate(-30, Y), rotate(165, X), standard lighting, pop on the native pose
     */
    @Overwrite
    public static void enableGUIStandardItemLighting() {
        Lighting.enableGui();
    }
}
