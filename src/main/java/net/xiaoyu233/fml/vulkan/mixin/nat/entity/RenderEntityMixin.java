package net.xiaoyu233.fml.vulkan.mixin.nat.entity;

import net.minecraft.AxisAlignedBB;
import net.minecraft.Block;
import net.minecraft.Entity;
import net.minecraft.EntityLiving;
import net.minecraft.Icon;
import net.minecraft.MathHelper;
import net.minecraft.Minecraft;
import net.minecraft.Render;
import net.minecraft.RenderManager;
import net.minecraft.ResourceLocation;
import net.minecraft.TextureMap;
import net.minecraft.Tessellator;
import net.minecraft.World;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native Render: entity fire, shadow and AABB drawing through {@link Draw} (ARCHITECTURE-NATIVE 3.7). */
@Mixin(Render.class)
public abstract class RenderEntityMixin {
    @Shadow @Final private static ResourceLocation shadowTextures;
    @Shadow protected RenderManager renderManager;
    @Shadow protected float shadowSize;

    @Shadow protected abstract void bindTexture(ResourceLocation location);

    @Shadow private native World getWorldFromRenderManager();

    @Shadow private native void renderShadowOnBlockMITE(double par2, double par4, double par6, int blockX, int blockZ, float par11, float par12,
                                                        double par13, double par15, double par17, float opacityOfObject, Entity entity);

    /** @author fml-native @reason native renderer */
    @Overwrite
    private void renderEntityOnFire(Entity par1Entity, double par2, double par4, double par6, float par8) {
        Draw.lighting(false);
        Icon var9 = Block.fire.getFireIcon(0);
        Icon var10 = Block.fire.getFireIcon(1);
        Draw.pushPose();
        Draw.translate((float) par2, (float) par4, (float) par6);
        float var11 = par1Entity.width * 1.4F;
        Draw.scale(var11, var11, var11);
        Tessellator var12 = Tessellator.instance;
        float var13 = 0.5F;
        float var14 = 0.0F;
        float var15 = par1Entity.height / var11;
        float var16 = (float) (par1Entity.posY - par1Entity.boundingBox.minY);
        Draw.rotate(-this.renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
        Draw.translate(0.0F, 0.0F, -0.3F + (int) var15 * 0.02F);
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
        float var17 = 0.0F;
        int var18 = 0;
        var12.startDrawingQuads();

        while (var15 > 0.0F) {
            Icon var19 = var18 % 2 == 0 ? var9 : var10;
            this.bindTexture(TextureMap.locationBlocksTexture);
            float var20 = var19.getMinU();
            float var21 = var19.getMinV();
            float var22 = var19.getMaxU();
            float var23 = var19.getMaxV();
            if (var18 / 2 % 2 == 0) {
                float var24 = var22;
                var22 = var20;
                var20 = var24;
            }

            var12.addVertexWithUV(var13 - var14, 0.0F - var16, var17, var22, var23);
            var12.addVertexWithUV(-var13 - var14, 0.0F - var16, var17, var20, var23);
            var12.addVertexWithUV(-var13 - var14, 1.4F - var16, var17, var20, var21);
            var12.addVertexWithUV(var13 - var14, 1.4F - var16, var17, var22, var21);
            var15 -= 0.45F;
            var16 -= 0.45F;
            var13 *= 0.9F;
            var17 += 0.03F;
            var18++;
        }

        var12.draw();
        Draw.popPose();
        Draw.lighting(true);
    }

    /** @author fml-native @reason native renderer */
    @Overwrite
    private void renderShadow(Entity par1Entity, double par2, double par4, double par6, float par8, float par9) {
        if (!par1Entity.disable_shadow) {
            par4 -= par1Entity.yOffset;
            Draw.blend(true);
            Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
            this.renderManager.renderEngine.bindTexture(shadowTextures);
            World var10 = this.getWorldFromRenderManager();
            Draw.depthMask(false);
            float var11 = this.shadowSize;
            if (par1Entity instanceof EntityLiving) {
                EntityLiving var12 = (EntityLiving) par1Entity;
                var11 *= var12.getRenderSizeModifier();
                if (var12.isChild()) {
                    var11 *= 0.5F;
                }
            }

            double var35 = par1Entity.lastTickPosX + (par1Entity.posX - par1Entity.lastTickPosX) * par9;
            double var14 = par1Entity.lastTickPosY + (par1Entity.posY - par1Entity.lastTickPosY) * par9 + par1Entity.getShadowSize();
            var14 -= par1Entity.yOffset;
            double var16 = par1Entity.lastTickPosZ + (par1Entity.posZ - par1Entity.lastTickPosZ) * par9;
            int var18 = MathHelper.floor_double(var35 - var11);
            int var19 = MathHelper.floor_double(var35 + var11);
            int var22 = MathHelper.floor_double(var16 - var11);
            int var23 = MathHelper.floor_double(var16 + var11);
            double var24 = par2 - var35;
            double var26 = par4 - var14;
            double var28 = par6 - var16;
            Tessellator var30 = Tessellator.instance;
            var30.startDrawingQuads();
            float shadow_size = par1Entity.getShadowSize();
            float object_opacity = this.renderManager.getEntityRenderObject(par1Entity).getModelOpacity(par1Entity);
            Draw.alphaFunc(0.001F);

            for (int x = var18; x <= var19; x++) {
                for (int z = var22; z <= var23; z++) {
                    this.renderShadowOnBlockMITE(par2, par4 + shadow_size, par6, x, z, par8, var11, var24, var26 + shadow_size, var28, object_opacity, par1Entity);
                }
            }

            var30.draw();
            Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
            Draw.blend(false);
            Draw.depthMask(true);
            Draw.alphaFunc(0.1F);
        }
    }

    /** Deprecated in MITE (reports an error); only the alpha func call is GL. @author fml-native @reason native renderer */
    @Overwrite
    private void renderShadowOnBlock(Block par1Block, double par2, double par4, double par6, int par8, int par9, int par10, float par11, float par12,
                                     double par13, double par15, double par17, float opacity_of_object, Entity entity) {
        Minecraft.setErrorMessage("renderShadowOnBlock: This function is no longer in service");
        Draw.alphaFunc(0.01F);
        // MITE body past this point is unreachable in practice (function out of service) and only emits geometry; omitted.
    }

    /** @author fml-native @reason native renderer */
    @Overwrite
    public static void renderOffsetAABB(AxisAlignedBB par0AxisAlignedBB, double par1, double par3, double par5) {
        Draw.texturing(false);
        Tessellator var7 = Tessellator.instance;
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
        var7.startDrawingQuads();
        var7.setTranslation(par1, par3, par5);
        var7.setNormal(0.0F, 0.0F, -1.0F);
        var7.addVertex(par0AxisAlignedBB.minX, par0AxisAlignedBB.maxY, par0AxisAlignedBB.minZ);
        var7.addVertex(par0AxisAlignedBB.maxX, par0AxisAlignedBB.maxY, par0AxisAlignedBB.minZ);
        var7.addVertex(par0AxisAlignedBB.maxX, par0AxisAlignedBB.minY, par0AxisAlignedBB.minZ);
        var7.addVertex(par0AxisAlignedBB.minX, par0AxisAlignedBB.minY, par0AxisAlignedBB.minZ);
        var7.setNormal(0.0F, 0.0F, 1.0F);
        var7.addVertex(par0AxisAlignedBB.minX, par0AxisAlignedBB.minY, par0AxisAlignedBB.maxZ);
        var7.addVertex(par0AxisAlignedBB.maxX, par0AxisAlignedBB.minY, par0AxisAlignedBB.maxZ);
        var7.addVertex(par0AxisAlignedBB.maxX, par0AxisAlignedBB.maxY, par0AxisAlignedBB.maxZ);
        var7.addVertex(par0AxisAlignedBB.minX, par0AxisAlignedBB.maxY, par0AxisAlignedBB.maxZ);
        var7.setNormal(0.0F, -1.0F, 0.0F);
        var7.addVertex(par0AxisAlignedBB.minX, par0AxisAlignedBB.minY, par0AxisAlignedBB.minZ);
        var7.addVertex(par0AxisAlignedBB.maxX, par0AxisAlignedBB.minY, par0AxisAlignedBB.minZ);
        var7.addVertex(par0AxisAlignedBB.maxX, par0AxisAlignedBB.minY, par0AxisAlignedBB.maxZ);
        var7.addVertex(par0AxisAlignedBB.minX, par0AxisAlignedBB.minY, par0AxisAlignedBB.maxZ);
        var7.setNormal(0.0F, 1.0F, 0.0F);
        var7.addVertex(par0AxisAlignedBB.minX, par0AxisAlignedBB.maxY, par0AxisAlignedBB.maxZ);
        var7.addVertex(par0AxisAlignedBB.maxX, par0AxisAlignedBB.maxY, par0AxisAlignedBB.maxZ);
        var7.addVertex(par0AxisAlignedBB.maxX, par0AxisAlignedBB.maxY, par0AxisAlignedBB.minZ);
        var7.addVertex(par0AxisAlignedBB.minX, par0AxisAlignedBB.maxY, par0AxisAlignedBB.minZ);
        var7.setNormal(-1.0F, 0.0F, 0.0F);
        var7.addVertex(par0AxisAlignedBB.minX, par0AxisAlignedBB.minY, par0AxisAlignedBB.maxZ);
        var7.addVertex(par0AxisAlignedBB.minX, par0AxisAlignedBB.maxY, par0AxisAlignedBB.maxZ);
        var7.addVertex(par0AxisAlignedBB.minX, par0AxisAlignedBB.maxY, par0AxisAlignedBB.minZ);
        var7.addVertex(par0AxisAlignedBB.minX, par0AxisAlignedBB.minY, par0AxisAlignedBB.minZ);
        var7.setNormal(1.0F, 0.0F, 0.0F);
        var7.addVertex(par0AxisAlignedBB.maxX, par0AxisAlignedBB.minY, par0AxisAlignedBB.minZ);
        var7.addVertex(par0AxisAlignedBB.maxX, par0AxisAlignedBB.maxY, par0AxisAlignedBB.minZ);
        var7.addVertex(par0AxisAlignedBB.maxX, par0AxisAlignedBB.maxY, par0AxisAlignedBB.maxZ);
        var7.addVertex(par0AxisAlignedBB.maxX, par0AxisAlignedBB.minY, par0AxisAlignedBB.maxZ);
        var7.setTranslation(0.0, 0.0, 0.0);
        var7.draw();
        Draw.texturing(true);
    }
}
