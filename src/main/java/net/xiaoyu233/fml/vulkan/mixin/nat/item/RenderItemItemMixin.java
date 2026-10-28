package net.xiaoyu233.fml.vulkan.mixin.nat.item;

import java.util.Random;
import net.minecraft.Block;
import net.minecraft.Render;
import net.minecraft.FontRenderer;
import net.minecraft.EntityItem;
import net.minecraft.Entity;
import net.minecraft.Icon;
import net.minecraft.Item;
import net.minecraft.ItemRenderer;
import net.minecraft.ItemStack;
import net.minecraft.MathHelper;
import net.minecraft.Minecraft;
import net.minecraft.RenderBlocks;
import net.minecraft.RenderItem;
import net.minecraft.RenderManager;
import net.minecraft.ResourceLocation;
import net.minecraft.Tessellator;
import net.minecraft.TextureManager;
import net.minecraft.TextureMap;
import net.xiaoyu233.fml.vulkan.render.item.ItemEffects;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Native area "item" (NR-14, template C): MITE {@code RenderItem} with every GL11 call replaced by {@code Draw}/{@code ItemEffects}.
 * Arithmetic, ordering and method signatures are MITE's (Twilight Forest hooks on doRenderItem/renderItemIntoGUI stay injectable).
 */
@Mixin(value = RenderItem.class, remap = false)
public abstract class RenderItemItemMixin extends Render {
    @Shadow @Final private static ResourceLocation RES_ITEM_GLINT;
    @Shadow private RenderBlocks itemRenderBlocks;
    @Shadow private Random random;
    @Shadow public boolean renderWithColor;
    @Shadow public float zLevel;

    @Shadow public static boolean renderInFrame;
    @Shadow protected abstract void renderQuad(Tessellator tess, int x, int y, int w, int h, int color);
    @Shadow public abstract void renderIcon(int x, int y, Icon icon, int w, int h);

    /** @author fml-native @reason native renderer: GL11 state calls replaced by Draw */
    @Overwrite
    public void doRenderItem(EntityItem par1EntityItem, double par2, double par4, double par6, float par8, float par9) {
      this.bindEntityTexture(par1EntityItem);
      this.random.setSeed(187L);
      ItemStack var10 = par1EntityItem.getEntityItem();
      if (var10.getItem() != null) {
         Draw.pushPose();
         float var11 = MathHelper.sin((par1EntityItem.age + par9) / 10.0F + par1EntityItem.hoverStart) * 0.1F + 0.1F;
         float var12 = ((par1EntityItem.age + par9) / 20.0F + par1EntityItem.hoverStart) * (180.0F / (float)Math.PI);
         byte var13 = 1;
         if (par1EntityItem.getEntityItem().stackSize > 1) {
            var13 = 2;
         }

         if (par1EntityItem.getEntityItem().stackSize > 5) {
            var13 = 3;
         }

         if (par1EntityItem.getEntityItem().stackSize > 20) {
            var13 = 4;
         }

         if (par1EntityItem.getEntityItem().stackSize > 40) {
            var13 = 5;
         }

         Draw.translate((float)par2, (float)par4 + var11, (float)par6);
         Draw.rescaleNormal(true);
         if (var10.getItemSpriteNumber() == 0
            && var10.itemID < Block.blocksList.length
            && Block.blocksList[var10.itemID] != null
            && RenderBlocks.renderItemIn3d(Block.blocksList[var10.itemID].getRenderType())) {
            Block var21 = Block.blocksList[var10.itemID];
            Draw.rotate(var12, 0.0F, 1.0F, 0.0F);
            if (renderInFrame) {
               Draw.scale(1.25F, 1.25F, 1.25F);
               Draw.translate(0.0F, 0.05F, 0.0F);
               Draw.rotate(-90.0F, 0.0F, 1.0F, 0.0F);
            }

            float var25 = 0.25F;
            int var24 = var21.getRenderType();
            if (var24 == 1 || var24 == 19 || var24 == 12 || var24 == 2) {
               var25 = 0.5F;
            }

            Draw.scale(var25, var25, var25);

            for (int var26 = 0; var26 < var13; var26++) {
               Draw.pushPose();
               if (var26 > 0) {
                  float var18 = (this.random.nextFloat() * 2.0F - 1.0F) * 0.2F / var25;
                  float var19 = (this.random.nextFloat() * 2.0F - 1.0F) * 0.2F / var25;
                  float var20 = (this.random.nextFloat() * 2.0F - 1.0F) * 0.2F / var25;
                  Draw.translate(var18, var19, var20);
               }

               float var18 = 1.0F;
               this.itemRenderBlocks.renderBlockAsItem(var21, var10.getItemSubtype(), var18);
               Draw.popPose();
            }
         } else if (var10.getItemSpriteNumber() == 1 && var10.getItem().requiresMultipleRenderPasses()) {
            if (renderInFrame) {
               Draw.scale(0.5128205F, 0.5128205F, 0.5128205F);
               Draw.translate(0.0F, -0.05F, 0.0F);
            } else {
               Draw.scale(0.5F, 0.5F, 0.5F);
            }

            for (int var23 = 0; var23 <= 1; var23++) {
               this.random.setSeed(187L);
               Icon var22 = var10.getItem().getIconFromSubtypeForRenderPass(var10.getItemSubtype(), var23);
               float var16 = 1.0F;
               if (this.renderWithColor) {
                  int var26 = Item.itemsList[var10.itemID].getColorFromItemStack(var10, var23);
                  float var18 = (var26 >> 16 & 0xFF) / 255.0F;
                  float var19 = (var26 >> 8 & 0xFF) / 255.0F;
                  float var20 = (var26 & 0xFF) / 255.0F;
                  Draw.color(var18 * var16, var19 * var16, var20 * var16, 1.0F);
                  this.renderDroppedItem(par1EntityItem, var22, var13, par9, var18 * var16, var19 * var16, var20 * var16);
               } else {
                  this.renderDroppedItem(par1EntityItem, var22, var13, par9, 1.0F, 1.0F, 1.0F);
               }
            }
         } else {
            if (renderInFrame) {
               Draw.scale(0.5128205F, 0.5128205F, 0.5128205F);
               Draw.translate(0.0F, -0.05F, 0.0F);
            } else {
               Draw.scale(0.5F, 0.5F, 0.5F);
            }

            Icon var14 = var10.getIconIndex();
            if (this.renderWithColor) {
               int var15 = Item.itemsList[var10.itemID].getColorFromItemStack(var10, 0);
               float var16 = (var15 >> 16 & 0xFF) / 255.0F;
               float var17 = (var15 >> 8 & 0xFF) / 255.0F;
               float var18 = (var15 & 0xFF) / 255.0F;
               float var19 = 1.0F;
               this.renderDroppedItem(par1EntityItem, var14, var13, par9, var16 * var19, var17 * var19, var18 * var19);
            } else {
               this.renderDroppedItem(par1EntityItem, var14, var13, par9, 1.0F, 1.0F, 1.0F);
            }
         }

         Draw.rescaleNormal(false);
         Draw.popPose();
      }
   }

    /** @author fml-native @reason native renderer: GL11 state calls replaced by Draw */
    @Overwrite
    private void renderDroppedItem(EntityItem par1EntityItem, Icon par2Icon, int par3, float par4, float par5, float par6, float par7) {
      Tessellator var8 = Tessellator.instance;
      if (par2Icon == null) {
         TextureManager var9 = Minecraft.getMinecraft().getTextureManager();
         ResourceLocation var10 = var9.getResourceLocation(par1EntityItem.getEntityItem().getItemSpriteNumber());
         par2Icon = ((TextureMap)var9.getTexture(var10)).getAtlasSprite("missingno");
      }

      float var25 = par2Icon.getMinU();
      float var26 = par2Icon.getMaxU();
      float var11 = par2Icon.getMinV();
      float var12 = par2Icon.getMaxV();
      float var13 = 1.0F;
      float var14 = 0.5F;
      float var15 = 0.25F;
      if (this.renderManager.options.isFancyGraphicsEnabled()) {
         Draw.pushPose();
         if (renderInFrame) {
            Draw.rotate(180.0F, 0.0F, 1.0F, 0.0F);
         } else {
            Draw.rotate(((par1EntityItem.age + par4) / 20.0F + par1EntityItem.hoverStart) * (180.0F / (float)Math.PI), 0.0F, 1.0F, 0.0F);
         }

         float var16 = 0.0625F;
         float var17 = 0.021875F;
         ItemStack var18 = par1EntityItem.getEntityItem();
         int var19 = var18.stackSize;
         byte var24;
         if (var19 < 2) {
            var24 = 1;
         } else if (var19 < 16) {
            var24 = 2;
         } else if (var19 < 32) {
            var24 = 3;
         } else {
            var24 = 4;
         }

         Draw.translate(-var14, -var15, -((var16 + var17) * var24 / 2.0F));

         for (int var20 = 0; var20 < var24; var20++) {
            Draw.translate(0.0F, 0.0F, var16 + var17);
            if (var18.getItemSpriteNumber() == 0 && Block.blocksList[var18.itemID] != null) {
               this.bindTexture(TextureMap.locationBlocksTexture);
            } else {
               this.bindTexture(TextureMap.locationItemsTexture);
            }

            Draw.color(par5, par6, par7, 1.0F);
            ItemRenderer.renderItemIn2D(var8, var26, var11, var25, var12, par2Icon.getIconWidth(), par2Icon.getIconHeight(), var16);
            if (var18.hasEffect()) {
               ItemEffects.beginWorldGlint(0.76F);
               this.renderManager.renderEngine.bindTexture(RES_ITEM_GLINT);
               ItemEffects.beginGlintPass(0, Minecraft.getSystemTime());
               ItemRenderer.renderItemIn2D(var8, 0.0F, 0.0F, 1.0F, 1.0F, 255, 255, var16);
               ItemEffects.endGlintPass();
               ItemEffects.beginGlintPass(1, Minecraft.getSystemTime());
               ItemRenderer.renderItemIn2D(var8, 0.0F, 0.0F, 1.0F, 1.0F, 255, 255, var16);
               ItemEffects.endGlintPass();
               ItemEffects.endWorldGlint();
            }
         }

         Draw.popPose();
      } else {
         for (int var27 = 0; var27 < par3; var27++) {
            Draw.pushPose();
            if (var27 > 0) {
               float var17 = (this.random.nextFloat() * 2.0F - 1.0F) * 0.3F;
               float var29 = (this.random.nextFloat() * 2.0F - 1.0F) * 0.3F;
               float var28 = (this.random.nextFloat() * 2.0F - 1.0F) * 0.3F;
               Draw.translate(var17, var29, var28);
            }

            if (!renderInFrame) {
               Draw.rotate(180.0F - this.renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
            }

            Draw.color(par5, par6, par7, 1.0F);
            var8.startDrawingQuads();
            var8.setNormal(0.0F, 1.0F, 0.0F);
            var8.addVertexWithUV(0.0F - var14, 0.0F - var15, 0.0, var25, var12);
            var8.addVertexWithUV(var13 - var14, 0.0F - var15, 0.0, var26, var12);
            var8.addVertexWithUV(var13 - var14, 1.0F - var15, 0.0, var26, var11);
            var8.addVertexWithUV(0.0F - var14, 1.0F - var15, 0.0, var25, var11);
            var8.draw();
            Draw.popPose();
         }
      }
   }

    /** @author fml-native @reason native renderer: GL11 state calls replaced by Draw */
    @Overwrite
    public void renderItemIntoGUI(FontRenderer par1FontRenderer, TextureManager par2TextureManager, ItemStack par3ItemStack, int par4, int par5) {
      int var6 = par3ItemStack.itemID;
      int var7 = par3ItemStack.getItemSubtype();
      Object var8 = par3ItemStack.getIconIndex();
      if (par3ItemStack.getItemSpriteNumber() == 0 && RenderBlocks.renderItemIn3d(Block.blocksList[var6].getRenderType())) {
         par2TextureManager.bindTexture(TextureMap.locationBlocksTexture);
         Block var15 = Block.blocksList[var6];
         Draw.pushPose();
         Draw.translate(par4 - 2, par5 + 3, -3.0F + this.zLevel);
         Draw.scale(10.0F, 10.0F, 10.0F);
         Draw.translate(1.0F, 0.5F, 1.0F);
         Draw.scale(1.0F, 1.0F, -1.0F);
         Draw.rotate(210.0F, 1.0F, 0.0F, 0.0F);
         Draw.rotate(45.0F, 0.0F, 1.0F, 0.0F);
         int var18 = Item.itemsList[var6].getColorFromItemStack(par3ItemStack, 0);
         float var17 = (var18 >> 16 & 0xFF) / 255.0F;
         float var12 = (var18 >> 8 & 0xFF) / 255.0F;
         float var13 = (var18 & 0xFF) / 255.0F;
         if (this.renderWithColor) {
            Draw.color(var17, var12, var13, 1.0F);
         }

         Draw.rotate(-90.0F, 0.0F, 1.0F, 0.0F);
         this.itemRenderBlocks.useInventoryTint = this.renderWithColor;
         this.itemRenderBlocks.renderBlockAsItem(var15, var7, 1.0F);
         this.itemRenderBlocks.useInventoryTint = true;
         Draw.popPose();
      } else if (Item.itemsList[var6].requiresMultipleRenderPasses()) {
         Draw.lighting(false);
         par2TextureManager.bindTexture(TextureMap.locationItemsTexture);

         for (int var9 = 0; var9 <= 1; var9++) {
            Icon var10 = Item.itemsList[var6].getIconFromSubtypeForRenderPass(var7, var9);
            int var11 = Item.itemsList[var6].getColorFromItemStack(par3ItemStack, var9);
            float var12 = (var11 >> 16 & 0xFF) / 255.0F;
            float var13 = (var11 >> 8 & 0xFF) / 255.0F;
            float var14 = (var11 & 0xFF) / 255.0F;
            if (this.renderWithColor) {
               Draw.color(var12, var13, var14, 1.0F);
            }

            this.renderIcon(par4, par5, var10, 16, 16);
         }

         Draw.lighting(true);
      } else {
         Draw.lighting(false);
         ResourceLocation var16 = par2TextureManager.getResourceLocation(par3ItemStack.getItemSpriteNumber());
         par2TextureManager.bindTexture(var16);
         if (var8 == null) {
            var8 = ((TextureMap)Minecraft.getMinecraft().getTextureManager().getTexture(var16)).getAtlasSprite("missingno");
         }

         int var18 = Item.itemsList[var6].getColorFromItemStack(par3ItemStack, 0);
         float var17 = (var18 >> 16 & 0xFF) / 255.0F;
         float var12 = (var18 >> 8 & 0xFF) / 255.0F;
         float var13 = (var18 & 0xFF) / 255.0F;
         if (this.renderWithColor) {
            Draw.color(var17, var12, var13, 1.0F);
         }

         this.renderIcon(par4, par5, (Icon)var8, 16, 16);
         Draw.lighting(true);
      }

      Draw.cull(true);
   }

    /** @author fml-native @reason native renderer: GL11 state calls replaced by Draw */
    @Overwrite
    public void renderItemAndEffectIntoGUI(FontRenderer par1FontRenderer, TextureManager par2TextureManager, ItemStack par3ItemStack, int par4, int par5) {
      if (par3ItemStack != null) {
         this.renderItemIntoGUI(par1FontRenderer, par2TextureManager, par3ItemStack, par4, par5);
         if (par3ItemStack.hasEffect()) {
            ItemEffects.beginGuiGlint();
            par2TextureManager.bindTexture(RES_ITEM_GLINT);
            this.zLevel -= 50.0F;
            this.renderGlint(par4 * 431278612 + par5 * 32178161, par4 - 2, par5 - 2, 20, 20);
            this.zLevel += 50.0F;
            ItemEffects.endGuiGlint();
         }
      }
   }

    /** @author fml-native @reason native renderer: GL11 state calls replaced by Draw */
    @Overwrite
    private void renderGlint(int par1, int par2, int par3, int par4, int par5) {
      for (int var6 = 0; var6 < 2; var6++) {
         if (var6 == 0) {
            Draw.blendFunc(BlendFactor.SRC_COLOR, BlendFactor.ONE);
         }

         if (var6 == 1) {
            Draw.blendFunc(BlendFactor.SRC_COLOR, BlendFactor.ONE);
         }

         float var7 = 0.00390625F;
         float var8 = 0.00390625F;
         float var9 = (float)(Minecraft.getSystemTime() % (3000 + var6 * 1873)) / (3000.0F + var6 * 1873) * 256.0F;
         float var10 = 0.0F;
         Tessellator var11 = Tessellator.instance;
         float var12 = 4.0F;
         if (var6 == 1) {
            var12 = -1.0F;
         }

         var11.startDrawingQuads();
         var11.addVertexWithUV(par2 + 0, par3 + par5, this.zLevel, (var9 + par5 * var12) * var7, (var10 + par5) * var8);
         var11.addVertexWithUV(par2 + par4, par3 + par5, this.zLevel, (var9 + par4 + par5 * var12) * var7, (var10 + par5) * var8);
         var11.addVertexWithUV(par2 + par4, par3 + 0, this.zLevel, (var9 + par4) * var7, (var10 + 0.0F) * var8);
         var11.addVertexWithUV(par2 + 0, par3 + 0, this.zLevel, (var9 + 0.0F) * var7, (var10 + 0.0F) * var8);
         var11.draw();
      }
   }

    /** @author fml-native @reason native renderer: GL11 state calls replaced by Draw */
    @Overwrite
    public void renderItemOverlayIntoGUI(
      FontRenderer par1FontRenderer, TextureManager par2TextureManager, ItemStack par3ItemStack, int par4, int par5, String par6Str
   ) {
      if (par3ItemStack != null) {
         if (par3ItemStack.stackSize > 1 || par6Str != null) {
            String var7 = par6Str == null ? String.valueOf(par3ItemStack.stackSize) : par6Str;
            Draw.lighting(false);
            Draw.depthTest(false);
            par1FontRenderer.drawStringWithShadow(var7, par4 + 19 - 2 - par1FontRenderer.getStringWidth(var7), par5 + 6 + 3, 16777215);
            Draw.lighting(true);
            Draw.depthTest(true);
         }

         if (par3ItemStack.isItemDamaged()) {
            int var12 = (int)Math.round(13.0 - par3ItemStack.getItemDamageForDisplay() * 13.0 / par3ItemStack.getMaxDamage());
            int var8 = (int)Math.round(255.0 - par3ItemStack.getItemDamageForDisplay() * 255.0 / par3ItemStack.getMaxDamage());
            Draw.lighting(false);
            Draw.depthTest(false);
            Draw.texturing(false);
            Tessellator var9 = Tessellator.instance;
            int var10 = 255 - var8 << 16 | var8 << 8;
            int var11 = (255 - var8) / 4 << 16 | 16128;
            this.renderQuad(var9, par4 + 2, par5 + 13, 13, 2, 0);
            this.renderQuad(var9, par4 + 2, par5 + 13, 12, 1, var11);
            this.renderQuad(var9, par4 + 2, par5 + 13, var12, 1, var10);
            Draw.texturing(true);
            Draw.lighting(true);
            Draw.depthTest(true);
            Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
         }
      }
   }

}
