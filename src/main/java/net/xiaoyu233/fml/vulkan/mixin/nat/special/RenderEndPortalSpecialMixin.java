package net.xiaoyu233.fml.vulkan.mixin.nat.special;

import net.minecraft.RenderEndPortal;
import net.minecraft.TileEntityEndPortal;
import net.minecraft.TileEntitySpecialRenderer;
import net.xiaoyu233.fml.vulkan.render.special.PortalRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Native area "special": the end portal layers are drawn by {@link PortalRenderer} (PORTAL program, texgen on the CPU). */
@Mixin(RenderEndPortal.class)
public abstract class RenderEndPortalSpecialMixin extends TileEntitySpecialRenderer {
    private RenderEndPortalSpecialMixin() {
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    public void renderEndPortalTileEntity(TileEntityEndPortal portal, double x, double y, double z, float partialTicks) {
        PortalRenderer.draw(x, y, z, partialTicks, this.tileEntityRenderer);
    }
}
