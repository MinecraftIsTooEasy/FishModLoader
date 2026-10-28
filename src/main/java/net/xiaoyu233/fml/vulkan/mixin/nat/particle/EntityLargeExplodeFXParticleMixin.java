package net.xiaoyu233.fml.vulkan.mixin.nat.particle;

import net.minecraft.EntityFX;
import net.minecraft.EntityLargeExplodeFX;
import net.minecraft.ResourceLocation;
import net.minecraft.Tessellator;
import net.minecraft.TextureManager;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Lighting;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "particle": large explosion FX with GL state routed through Draw/Lighting. */
@Mixin(EntityLargeExplodeFX.class)
public abstract class EntityLargeExplodeFXParticleMixin extends EntityFX {
    @Shadow @Final private static ResourceLocation field_110127_a;
    @Shadow private int field_70581_a;
    @Shadow private int field_70584_aq;
    @Shadow private TextureManager theRenderEngine;
    @Shadow private float field_70582_as;

    protected EntityLargeExplodeFXParticleMixin() {
        super(null, 0.0, 0.0, 0.0);
    }

    /**
     * @author NR-15
     * @reason native renderer: GL11 state replaced by Draw.* / Lighting.*
     */
    @Overwrite
    public void renderParticle(Tessellator var1, float var2, float var3, float var4, float var5, float var6, float var7) {
        int var8 = (int) ((this.field_70581_a + var2) * 15.0F / this.field_70584_aq);
        if (var8 <= 15) {
            this.theRenderEngine.bindTexture(field_110127_a);
            float var9 = var8 % 4 / 4.0F;
            float var10 = var9 + 0.24975F;
            float var11 = var8 / 4 / 4.0F;
            float var12 = var11 + 0.24975F;
            float var13 = 2.0F * this.field_70582_as;
            float var14 = (float) (this.prevPosX + (this.posX - this.prevPosX) * var2 - interpPosX);
            float var15 = (float) (this.prevPosY + (this.posY - this.prevPosY) * var2 - interpPosY);
            float var16 = (float) (this.prevPosZ + (this.posZ - this.prevPosZ) * var2 - interpPosZ);
            Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
            Draw.lighting(false);
            Lighting.disable();
            var1.startDrawingQuads();
            var1.setColorRGBA_F(this.particleRed, this.particleGreen, this.particleBlue, 1.0F);
            var1.setNormal(0.0F, 1.0F, 0.0F);
            var1.setBrightness(240);
            var1.addVertexWithUV(var14 - var3 * var13 - var6 * var13, var15 - var4 * var13, var16 - var5 * var13 - var7 * var13, var10, var12);
            var1.addVertexWithUV(var14 - var3 * var13 + var6 * var13, var15 + var4 * var13, var16 - var5 * var13 + var7 * var13, var10, var11);
            var1.addVertexWithUV(var14 + var3 * var13 + var6 * var13, var15 + var4 * var13, var16 + var5 * var13 + var7 * var13, var9, var11);
            var1.addVertexWithUV(var14 + var3 * var13 - var6 * var13, var15 - var4 * var13, var16 + var5 * var13 - var7 * var13, var9, var12);
            var1.draw();
            Draw.polygonOffset(0.0F, 0.0F);
            Draw.lighting(true);
        }
    }
}
