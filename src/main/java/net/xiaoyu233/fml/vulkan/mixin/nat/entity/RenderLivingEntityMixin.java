package net.xiaoyu233.fml.vulkan.mixin.nat.entity;

import net.minecraft.Entity;
import net.minecraft.EntityChicken;
import net.minecraft.EntityCow;
import net.minecraft.EntityHanging;
import net.minecraft.EntityLiving;
import net.minecraft.EntityOcelot;
import net.minecraft.EntityPig;
import net.minecraft.EntitySheep;
import net.minecraft.EntityWolf;
import net.minecraft.RenderLiving;
import net.minecraft.Tessellator;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Native area "entity": the leash of {@code RenderLiving} (ARCHITECTURE-NATIVE 3.7). */
@Mixin(RenderLiving.class)
public abstract class RenderLivingEntityMixin {
   private static double lerp(double a, double b, double t) {
      return a + (b - a) * t;
   }

   /**
    * @author fml-native
    * @reason native renderer
    */
   @Overwrite
   protected void func_110827_b(EntityLiving par1EntityLiving, double par2, double par4, double par6, float par8, float par9) {
      Entity var10 = par1EntityLiving.getLeashedToEntity();
      if (var10 != null) {
         par4 -= (1.6 - par1EntityLiving.height) * 0.5;
         boolean is_child = par1EntityLiving.isChild();
         float height_adjustment;
         if (par1EntityLiving instanceof EntityChicken) {
            height_adjustment = is_child ? -0.45F : -0.35F;
         } else if (par1EntityLiving instanceof EntitySheep) {
            height_adjustment = is_child ? -0.3F : -0.1F;
         } else if (par1EntityLiving instanceof EntityPig) {
            height_adjustment = is_child ? -0.4F : -0.3F;
         } else if (par1EntityLiving instanceof EntityCow) {
            height_adjustment = is_child ? -0.25F : -0.0F;
         } else if (par1EntityLiving instanceof EntityWolf) {
            height_adjustment = is_child ? -0.45F : -0.2F;
         } else if (par1EntityLiving instanceof EntityOcelot) {
            height_adjustment = is_child ? -0.55F : -0.45F;
         } else {
            height_adjustment = 0.0F;
         }

         Tessellator var11 = Tessellator.instance;
         double var12 = lerp(var10.prevRotationYaw, var10.rotationYaw, par9 * 0.5F) * (float) (Math.PI / 180.0);
         double var14 = lerp(var10.prevRotationPitch, var10.rotationPitch, par9 * 0.5F) * (float) (Math.PI / 180.0);
         double var16 = Math.cos(var12);
         double var18 = Math.sin(var12);
         double var20 = Math.sin(var14);
         if (var10 instanceof EntityHanging) {
            var16 = 0.0;
            var18 = 0.0;
            var20 = -1.0;
         }

         double var22 = Math.cos(var14);
         double var24 = lerp(var10.prevPosX, var10.posX, par9) - var16 * 0.7 - var18 * 0.5 * var22;
         double var26 = lerp(var10.prevPosY + var10.getEyeHeight() * 0.7, var10.posY + var10.getEyeHeight() * 0.7, par9) - var20 * 0.5 - 0.25;
         double var28 = lerp(var10.prevPosZ, var10.posZ, par9) - var18 * 0.7 + var16 * 0.5 * var22;
         double var30 = lerp(par1EntityLiving.prevRenderYawOffset, par1EntityLiving.renderYawOffset, par9) * (float) (Math.PI / 180.0)
            + (Math.PI / 2);
         var16 = Math.cos(var30) * par1EntityLiving.width * 0.4;
         var18 = Math.sin(var30) * par1EntityLiving.width * 0.4;
         double var32 = lerp(par1EntityLiving.prevPosX, par1EntityLiving.posX, par9) + var16;
         double var34 = lerp(par1EntityLiving.prevPosY, par1EntityLiving.posY, par9);
         double var36 = lerp(par1EntityLiving.prevPosZ, par1EntityLiving.posZ, par9) + var18;
         par2 += var16;
         par6 += var18;
         double var38 = (float)(var24 - var32);
         double var40 = (float)(var26 - var34);
         double var42 = (float)(var28 - var36);
         Draw.texturing(false);
         Draw.lighting(false);
         Draw.cull(false);
         boolean var44 = true;
         double var45 = 0.025;
         var11.startDrawing(5);

         for (int var47 = 0; var47 <= 24; var47++) {
            if (var47 % 2 == 0) {
               var11.setColorRGBA_F(0.5F, 0.4F, 0.3F, 1.0F);
            } else {
               var11.setColorRGBA_F(0.35F, 0.28F, 0.21000001F, 1.0F);
            }

            float var48 = var47 / 24.0F;
            float height_adjustment_fraction = 1.0F - var48;
            double y_animal = height_adjustment * height_adjustment_fraction
               + par4
               + var40 * (var48 * var48 + var48) * 0.5
               + ((24.0F - var47) / 18.0F + 0.125F);
            var11.addVertex(par2 + var38 * var48 + 0.0, y_animal, par6 + var42 * var48);
            var11.addVertex(par2 + var38 * var48 + 0.025, y_animal + 0.025, par6 + var42 * var48);
         }

         var11.draw();
         var11.startDrawing(5);

         for (int var59 = 0; var59 <= 24; var59++) {
            if (var59 % 2 == 0) {
               var11.setColorRGBA_F(0.5F, 0.4F, 0.3F, 1.0F);
            } else {
               var11.setColorRGBA_F(0.35F, 0.28F, 0.21000001F, 1.0F);
            }

            float var48 = var59 / 24.0F;
            float height_adjustment_fraction = 1.0F - var48;
            double y_animal = height_adjustment * height_adjustment_fraction
               + par4
               + var40 * (var48 * var48 + var48) * 0.5
               + ((24.0F - var59) / 18.0F + 0.125F);
            var11.addVertex(par2 + var38 * var48 + 0.0, y_animal + 0.025, par6 + var42 * var48);
            var11.addVertex(par2 + var38 * var48 + 0.025, y_animal, par6 + var42 * var48 + 0.025);
         }

         var11.draw();
         Draw.lighting(true);
         Draw.texturing(true);
         Draw.cull(true);
      }
   }
}
