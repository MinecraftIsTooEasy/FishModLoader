package net.xiaoyu233.fml.vulkan.mixin.nat.world;

import net.minecraft.ActiveRenderInfo;
import net.minecraft.EntityPlayer;
import net.xiaoyu233.fml.vulkan.render.world.CameraMatrices;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Native area "world": camera billboard vectors computed from the pose/projection stacks, no GL readback. */
@Mixin(ActiveRenderInfo.class)
public abstract class ActiveRenderInfoWorldMixin {
    /**
     * @author fml-native
     * @reason native renderer: matrices come from Draw.pose()/projection(), viewport from Frame
     */
    @Overwrite
    public static void updateRenderInfo(EntityPlayer player, boolean thirdPersonFront) {
        CameraMatrices.updateActiveRenderInfo(player, thirdPersonFront);
    }
}
