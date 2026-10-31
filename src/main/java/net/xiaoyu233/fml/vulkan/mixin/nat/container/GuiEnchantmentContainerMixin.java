package net.xiaoyu233.fml.vulkan.mixin.nat.container;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.*;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Frame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "container": GuiEnchantment book drawing with its own viewport and perspective (ARCHITECTURE-NATIVE 3.11). */
@Mixin(GuiEnchantment.class)
public abstract class GuiEnchantmentContainerMixin extends GuiContainer {
    @Shadow private static ResourceLocation enchantingTableGuiTextures;
    @Shadow private static ResourceLocation enchantingTableBookTextures;
    @Shadow private static ModelBook bookModel;
    @Shadow private ContainerEnchantment containerEnchantment;
    @Shadow public float field_74213_p;
    @Shadow public float field_74212_q;
    @Shadow public float field_74209_t;
    @Shadow public float field_74208_u;

    private GuiEnchantmentContainerMixin(Container c) {
        super(c);
    }

    /**
     * Enchanting table GUI background: panel, the 3D book in its own viewport/perspective (restored afterwards) and the three option slots.
     *
     * @author fml-native
     * @reason native renderer: GL viewport/matrix/colour statements replaced by Frame/Draw calls
     */
    @Overwrite
    protected void drawGuiContainerBackgroundLayer(float par1, int par2, int par3) {
      Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
      this.mc.getTextureManager().bindTexture(enchantingTableGuiTextures);
      int var4 = (this.width - this.xSize) / 2;
      int var5 = (this.height - this.ySize) / 2;
      this.drawTexturedModalRect(var4, var5, 0, 0, this.xSize, this.ySize);
      Draw.pushPose();
      Draw.projection().push();
      try {
         Draw.projection().loadIdentity();
         ScaledResolution var6 = new ScaledResolution(this.mc.gameSettings, this.mc.displayWidth, this.mc.displayHeight);
         Frame.viewport(
            (var6.getScaledWidth() - 320) / 2 * var6.getScaleFactor(),
            (var6.getScaledHeight() - 240) / 2 * var6.getScaleFactor(),
            320 * var6.getScaleFactor(),
            240 * var6.getScaleFactor()
         );
         Draw.projection().translate(-0.34F, 0.23F, 0.0F);
         Draw.projection().perspective(90.0F, 1.3333334F, 9.0F, 80.0F);
         float var7 = 1.0F;
         Draw.pose().loadIdentity();
         RenderHelper.enableStandardItemLighting();
         Draw.translate(0.0F, 3.3F, -16.0F);
         Draw.scale(var7, var7, var7);
         float var8 = 5.0F;
         Draw.scale(var8, var8, var8);
         Draw.rotate(180.0F, 0.0F, 0.0F, 1.0F);
         this.mc.getTextureManager().bindTexture(enchantingTableBookTextures);
         Draw.rotate(20.0F, 1.0F, 0.0F, 0.0F);
         float var9 = this.field_74208_u + (this.field_74209_t - this.field_74208_u) * par1;
         Draw.translate((1.0F - var9) * 0.2F, (1.0F - var9) * 0.1F, (1.0F - var9) * 0.25F);
         Draw.rotate(-(1.0F - var9) * 90.0F - 90.0F, 0.0F, 1.0F, 0.0F);
         Draw.rotate(180.0F, 1.0F, 0.0F, 0.0F);
         float var10 = this.field_74212_q + (this.field_74213_p - this.field_74212_q) * par1 + 0.25F;
         float var11 = this.field_74212_q + (this.field_74213_p - this.field_74212_q) * par1 + 0.75F;
         var10 = (var10 - MathHelper.truncateDoubleToInt(var10)) * 1.6F - 0.3F;
         var11 = (var11 - MathHelper.truncateDoubleToInt(var11)) * 1.6F - 0.3F;
         if (var10 < 0.0F) {
            var10 = 0.0F;
         }

         if (var11 < 0.0F) {
            var11 = 0.0F;
         }

         if (var10 > 1.0F) {
            var10 = 1.0F;
         }

         if (var11 > 1.0F) {
            var11 = 1.0F;
         }

         Draw.rescaleNormal(true);
         bookModel.render((Entity)null, 0.0F, var10, var11, var9, 0.0F, 0.0625F);
         Draw.rescaleNormal(false);
         RenderHelper.disableStandardItemLighting();
      } finally {
         Frame.viewportFull();
         Draw.projection().pop();
         Draw.popPose();
      }
      RenderHelper.disableStandardItemLighting();
      Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
      EnchantmentNameParts.instance.setRandSeed(this.containerEnchantment.nameSeed);
      List tooltips = null;

      for (int var12 = 0; var12 < 3; var12++) {
         String var13 = EnchantmentNameParts.instance.generateRandomEnchantName();
         this.zLevel = 0.0F;
         this.mc.getTextureManager().bindTexture(enchantingTableGuiTextures);
         int var14 = this.containerEnchantment.enchantLevels[var12];
         int experience_cost = Enchantment.getExperienceCost(var14);
         Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
         if (var14 == 0) {
            this.drawTexturedModalRect(var4 + 60, var5 + 14 + 19 * var12, 0, 185, 108, 19);
         } else {
            String var15 = "" + experience_cost;
            FontRenderer var16 = this.mc.standardGalacticFontRenderer;
            int var17 = 6839882;
            if (this.mc.thePlayer.experience < experience_cost && !this.mc.thePlayer.capabilities.isCreativeMode) {
               this.drawTexturedModalRect(var4 + 60, var5 + 14 + 19 * var12, 0, 185, 108, 19);
               var16.drawSplitString(var13, var4 + 62, var5 + 16 + 19 * var12, 104, (var17 & 16711422) >> 1);
               var16 = this.mc.fontRenderer;
               var17 = 4226832;
               var16.drawStringWithShadow(var15, var4 + 62 + 104 - var16.getStringWidth(var15), var5 + 16 + 19 * var12 + 7, var17);
            } else {
               int var18 = par2 - (var4 + 60);
               int var19 = par3 - (var5 + 14 + 19 * var12);
               if (var18 >= 0 && var19 >= 0 && var18 < 108 && var19 < 19) {
                  this.drawTexturedModalRect(var4 + 60, var5 + 14 + 19 * var12, 0, 204, 108, 19);
                  var17 = 16777088;
                  if (this.mc.theWorld.areSkillsEnabled() && !this.mc.thePlayer.hasSkill(Skill.ENCHANTING)) {
                     tooltips = new ArrayList();
                     tooltips.add(EnumChatFormatting.GRAY + "Requires " + Skill.ENCHANTING.getLocalizedName(true));
                  }
               } else {
                  this.drawTexturedModalRect(var4 + 60, var5 + 14 + 19 * var12, 0, 166, 108, 19);
               }

               var16.drawSplitString(var13, var4 + 62, var5 + 16 + 19 * var12, 104, var17);
               var16 = this.mc.fontRenderer;
               var17 = 8453920;
               var16.drawStringWithShadow(var15, var4 + 62 + 104 - var16.getStringWidth(var15), var5 + 16 + 19 * var12 + 7, var17);
            }
         }
      }

      if (tooltips != null) {
         this.func_102021_a(tooltips, par2, par3);
      }
   }
}
