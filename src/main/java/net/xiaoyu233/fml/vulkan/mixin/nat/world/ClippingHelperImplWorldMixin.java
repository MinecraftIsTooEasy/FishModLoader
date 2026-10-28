package net.xiaoyu233.fml.vulkan.mixin.nat.world;

import net.minecraft.ClippingHelper;
import net.minecraft.ClippingHelperImpl;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.world.CameraMatrices;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Native area "world": frustum planes computed from the pose/projection stacks, no GL readback. */
@Mixin(ClippingHelperImpl.class)
public abstract class ClippingHelperImplWorldMixin {
    /**
     * @author fml-native
     * @reason native renderer: matrices come from Draw.pose()/projection()
     */
    @Overwrite
    private void init() {
        CameraMatrices.computeFrustum((ClippingHelper) (Object) this, Draw.projection().top(), Draw.pose().top());
    }
}
