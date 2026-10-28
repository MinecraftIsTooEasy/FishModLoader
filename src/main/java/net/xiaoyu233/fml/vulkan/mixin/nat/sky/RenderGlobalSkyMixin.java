package net.xiaoyu233.fml.vulkan.mixin.nat.sky;

import net.minecraft.Minecraft;
import net.minecraft.RenderGlobal;
import net.minecraft.TextureManager;
import net.xiaoyu233.fml.vulkan.render.sky.SkyRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "sky": renderSky / renderStars of RenderGlobal (NR-11). {@code <init>} belongs to NR-09. */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalSkyMixin {
    @Shadow
    private Minecraft mc;
    @Shadow
    @Final
    private TextureManager renderEngine;

    /**
     * @author NR-11
     * @reason native renderer: sky drawn through Draw/NativeTess/StaticMeshes (the signature stays so Twilight Forest's HEAD injection applies)
     */
    @Overwrite
    public void renderSky(float par1) {
        SkyRenderer.renderSky(this.mc, this.renderEngine, par1);
    }

    /**
     * @author NR-11
     * @reason native renderer: star quads through NativeTess (called inside a StaticMeshes capture)
     */
    @Overwrite
    private void renderStars() {
        SkyRenderer.renderStars();
    }
}
