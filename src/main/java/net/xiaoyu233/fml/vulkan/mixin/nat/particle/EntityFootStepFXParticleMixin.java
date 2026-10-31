package net.xiaoyu233.fml.vulkan.mixin.nat.particle;

import net.minecraft.EntityFX;
import net.minecraft.EntityFootStepFX;
import net.minecraft.MathHelper;
import net.minecraft.ResourceLocation;
import net.minecraft.Tessellator;
import net.minecraft.TextureManager;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "particle": footprint FX with GL state routed through Draw. */
@Mixin(EntityFootStepFX.class)
public abstract class EntityFootStepFXParticleMixin extends EntityFX {
    @Shadow @Final private static ResourceLocation field_110126_a;
    @Shadow private int footstepAge;
    @Shadow private int footstepMaxAge;
    @Shadow private TextureManager currentFootSteps;

    protected EntityFootStepFXParticleMixin() {
        super(null, 0.0, 0.0, 0.0);
    }

    /**
     * @author NR-15
     * @reason native renderer: GL11 state replaced by Draw.*
     */
    @Overwrite
    public void renderParticle(Tessellator var1, float var2, float var3, float var4, float var5, float var6, float var7) {
        float var8 = (this.footstepAge + var2) / this.footstepMaxAge;
        var8 *= var8;
        float var9 = 2.0F - var8 * 2.0F;
        if (var9 > 1.0F) {
            var9 = 1.0F;
        }

        var9 *= 0.2F;
        Draw.lighting(false);
        float var10 = 0.125F;
        float var11 = (float) (this.posX - interpPosX);
        float var12 = (float) (this.posY - interpPosY);
        float var13 = (float) (this.posZ - interpPosZ);
        float var14 = this.worldObj
            .getLightBrightness(MathHelper.floor_double(this.posX), MathHelper.floor_double(this.posY), MathHelper.floor_double(this.posZ));
        this.currentFootSteps.bindTexture(field_110126_a);
        Draw.blend(true);
        Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
        var1.startDrawingQuads();
        var1.setColorRGBA_F(var14, var14, var14, var9);
        var1.addVertexWithUV(var11 - var10, var12, var13 + var10, 0.0, 1.0);
        var1.addVertexWithUV(var11 + var10, var12, var13 + var10, 1.0, 1.0);
        var1.addVertexWithUV(var11 + var10, var12, var13 - var10, 1.0, 0.0);
        var1.addVertexWithUV(var11 - var10, var12, var13 - var10, 0.0, 0.0);
        var1.draw();
        Draw.blend(false);
        Draw.lighting(true);
    }
}
