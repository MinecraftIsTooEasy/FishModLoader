package net.xiaoyu233.fml.vulkan.mixin.nat.entity;

import net.minecraft.*;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "entity": TileEntityRenderer dispatch without GL. */
@Mixin(TileEntityRenderer.class)
public abstract class TileEntityRendererEntityMixin {
   @Shadow public World worldObj;
   @Shadow public double playerX;
   @Shadow public double playerY;
   @Shadow public double playerZ;
   @Shadow public static double staticPlayerX;
   @Shadow public static double staticPlayerY;
   @Shadow public static double staticPlayerZ;
   @Shadow public abstract void renderTileEntityAt(TileEntity par1TileEntity, double par2, double par4, double par6, float par8);

   /**
    * @author fml-native
    * @reason native renderer
    */
   @Overwrite
   public void renderTileEntity(TileEntity par1TileEntity, float par2) {
      if (par1TileEntity.getDistanceFrom(this.playerX, this.playerY, this.playerZ) < par1TileEntity.getMaxRenderDistanceSquared()) {
         int var3 = this.worldObj.getLightBrightnessForSkyBlocks(par1TileEntity.xCoord, par1TileEntity.yCoord, par1TileEntity.zCoord, 0);
         int var4 = var3 % 65536;
         int var5 = var3 / 65536;
         OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, var4 / 1.0F, var5 / 1.0F);
         Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
         this.renderTileEntityAt(
            par1TileEntity, par1TileEntity.xCoord - staticPlayerX, par1TileEntity.yCoord - staticPlayerY, par1TileEntity.zCoord - staticPlayerZ, par2
         );
      }
   }
}
