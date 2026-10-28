package net.xiaoyu233.fml.vulkan.mixin.nat.special;

import net.minecraft.EntityLightningBolt;
import net.minecraft.Minecraft;
import net.minecraft.RenderLightningBolt;
import net.xiaoyu233.fml.vulkan.render.special.BeamRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Native area "special": lightning strips through {@link BeamRenderer}. */
@Mixin(RenderLightningBolt.class)
public abstract class RenderLightningBoltSpecialMixin {
    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    public void doRenderLightningBolt(EntityLightningBolt bolt, double x, double y, double z, float yaw, float partialTicks) {
        if (!(Minecraft.theMinecraft.raining_strength_for_render_view_entity < 0.5F)) {
            BeamRenderer.lightning(bolt.boltVertex, x, y, z);
        }
    }
}
