package net.xiaoyu233.fml.vulkan.mixin.nat.entity;

import net.minecraft.*;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "entity": RenderManager entity dispatch without GL. */
@Mixin(RenderManager.class)
public abstract class RenderManagerEntityMixin {
   @Shadow public TextureManager renderEngine;
   @Shadow public static double renderPosX;
   @Shadow public static double renderPosY;
   @Shadow public static double renderPosZ;
   @Shadow public static boolean field_85095_o;
   @Shadow public abstract Render getEntityRenderObject(Entity e);

   /**
    * @author fml-native
    * @reason native renderer
    */
   @Overwrite
   public void renderEntity(Entity par1Entity, float par2) {
      World world = par1Entity.worldObj;
      if (par1Entity instanceof EntityItem) {
         if (par1Entity.ticksExisted < 1) {
            return;
         }

         if (par1Entity.ticksExisted < 5
            && world.doBlockCollisionBoundsIntersectWithBB(
               par1Entity.getBlockPosX(), par1Entity.getBlockPosY(), par1Entity.getBlockPosZ(), par1Entity.boundingBox
            )) {
            return;
         }
      }

      Chunk chunk = world.getChunkFromBlockCoords(par1Entity.getBlockPosX(), par1Entity.getBlockPosZ());
      if (chunk != null && !chunk.isEmpty()) {
         int x = par1Entity.getBlockPosX();
         int y = par1Entity.getBlockPosY();
         int z = par1Entity.getBlockPosZ();
         RenderGlobal render_global = Minecraft.theMinecraft.renderGlobal;
         WorldRenderer world_renderer = null;
         if (par1Entity.index_of_last_applicable_world_renderer < render_global.worldRenderers.length) {
            world_renderer = render_global.worldRenderers[par1Entity.index_of_last_applicable_world_renderer];
            if (world_renderer == null || !world_renderer.isRenderingCoords(x, y, z)) {
               world_renderer = null;
            }
         }

         if (world_renderer == null) {
            world_renderer = Minecraft.theMinecraft.renderGlobal.getWorldRendererFor(x, y, z, par1Entity);
         }

         if (world_renderer != null && world_renderer.isInitialized) {
            if (par1Entity.ticksExisted == 0) {
               par1Entity.lastTickPosX = par1Entity.posX;
               par1Entity.lastTickPosY = par1Entity.posY;
               par1Entity.lastTickPosZ = par1Entity.posZ;
            }

            double var3 = par1Entity.lastTickPosX + (par1Entity.posX - par1Entity.lastTickPosX) * par2;
            double var5 = par1Entity.lastTickPosY + (par1Entity.posY - par1Entity.lastTickPosY) * par2;
            double var7 = par1Entity.lastTickPosZ + (par1Entity.posZ - par1Entity.lastTickPosZ) * par2;
            float var9 = par1Entity.prevRotationYaw + (par1Entity.rotationYaw - par1Entity.prevRotationYaw) * par2;
            int var10 = par1Entity.getBrightnessForRender(par2);
            if (par1Entity.isBurning()) {
               var10 = 15728880;
            }

            int var11 = var10 % 65536;
            int var12 = var10 / 65536;
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, var11 / 1.0F, var12 / 1.0F);
            Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
            this.renderEntityWithPosYaw(par1Entity, var3 - renderPosX, var5 - renderPosY, var7 - renderPosZ, var9, par2);
         }
      }
   }

   /**
    * @author fml-native
    * @reason native renderer
    */
   @Overwrite
   public void renderEntityWithPosYaw(Entity par1Entity, double par2, double par4, double par6, float par8, float par9) {
      Render var10 = null;

      try {
         var10 = this.getEntityRenderObject(par1Entity);
         if (var10 != null && this.renderEngine != null) {
            if (field_85095_o && !par1Entity.isInvisible()) {
               try {
                  this.func_85094_b(par1Entity, par2, par4, par6, par8, par9);
               } catch (Throwable var17) {
                  throw new ReportedException(CrashReport.makeCrashReport(var17, "Rendering entity hitbox in world"));
               }
            }

            try {
               var10.doRender(par1Entity, par2, par4, par6, par8, par9);
            } catch (Throwable var16) {
               throw new ReportedException(CrashReport.makeCrashReport(var16, "Rendering entity in world"));
            }

            try {
               var10.doRenderShadowAndFire(par1Entity, par2, par4, par6, par8, par9);
            } catch (Throwable var15) {
               throw new ReportedException(CrashReport.makeCrashReport(var15, "Post-rendering entity in world"));
            }
         }
      } catch (Throwable var18) {
         CrashReport var12 = CrashReport.makeCrashReport(var18, "Rendering entity in world");
         CrashReportCategory var13 = var12.makeCategory("Entity being rendered");
         par1Entity.addEntityCrashInfo(var13);
         CrashReportCategory var14 = var12.makeCategory("Renderer details");
         var14.addCrashSection("Assigned renderer", var10);
         var14.addCrashSection("Location", CrashReportCategory.func_85074_a(par2, par4, par6));
         var14.addCrashSection("Rotation", par8);
         var14.addCrashSection("Delta", par9);
         throw new ReportedException(var12);
      }
   }

   /**
    * @author fml-native
    * @reason native renderer
    */
   @Overwrite
   private void func_85094_b(Entity par1Entity, double par2, double par4, double par6, float par8, float par9) {
      if (par1Entity instanceof EntityClientPlayerMP) {
         par4 -= par1Entity.yOffset;
      }

      Draw.depthMask(false);
      Draw.texturing(false);
      Draw.lighting(false);
      Draw.cull(false);
      Draw.blend(false);
      Draw.pushPose();
      Tessellator var10 = Tessellator.instance;
      var10.startDrawingQuads();
      var10.setColorRGBA(255, 255, 255, 32);
      double var11 = -par1Entity.width / 2.0F;
      double var13 = -par1Entity.width / 2.0F;
      double var15 = par1Entity.width / 2.0F;
      double var17 = -par1Entity.width / 2.0F;
      double var19 = -par1Entity.width / 2.0F;
      double var21 = par1Entity.width / 2.0F;
      double var23 = par1Entity.width / 2.0F;
      double var25 = par1Entity.width / 2.0F;
      double var27 = par1Entity.height;
      var10.addVertex(par2 + var11, par4 + var27, par6 + var13);
      var10.addVertex(par2 + var11, par4, par6 + var13);
      var10.addVertex(par2 + var15, par4, par6 + var17);
      var10.addVertex(par2 + var15, par4 + var27, par6 + var17);
      var10.addVertex(par2 + var23, par4 + var27, par6 + var25);
      var10.addVertex(par2 + var23, par4, par6 + var25);
      var10.addVertex(par2 + var19, par4, par6 + var21);
      var10.addVertex(par2 + var19, par4 + var27, par6 + var21);
      var10.addVertex(par2 + var15, par4 + var27, par6 + var17);
      var10.addVertex(par2 + var15, par4, par6 + var17);
      var10.addVertex(par2 + var23, par4, par6 + var25);
      var10.addVertex(par2 + var23, par4 + var27, par6 + var25);
      var10.addVertex(par2 + var19, par4 + var27, par6 + var21);
      var10.addVertex(par2 + var19, par4, par6 + var21);
      var10.addVertex(par2 + var11, par4, par6 + var13);
      var10.addVertex(par2 + var11, par4 + var27, par6 + var13);
      var10.draw();
      Draw.popPose();
      Draw.texturing(true);
      Draw.lighting(true);
      Draw.cull(true);
      Draw.blend(false);
      Draw.depthMask(true);
   }

}
