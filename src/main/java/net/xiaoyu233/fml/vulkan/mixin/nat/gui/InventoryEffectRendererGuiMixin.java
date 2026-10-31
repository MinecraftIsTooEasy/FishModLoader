package net.xiaoyu233.fml.vulkan.mixin.nat.gui;

import java.util.Collection;
import net.minecraft.Curse;
import net.minecraft.EnumChatFormatting;
import net.minecraft.EnumInsulinResistanceLevel;
import net.minecraft.GuiContainer;
import net.minecraft.I18n;
import net.minecraft.InventoryEffectRenderer;
import net.minecraft.Container;
import net.minecraft.Potion;
import net.minecraft.PotionEffect;
import net.minecraft.ResourceLocation;
import net.minecraft.StringUtils;
import net.minecraft.Translator;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "gui": potion/debuff panel with Draw colour and lighting state. */
@Mixin(InventoryEffectRenderer.class)
public abstract class InventoryEffectRendererGuiMixin extends GuiContainer {
     @Shadow
     private int initial_tick;
     @Shadow @Final
     private static ResourceLocation sugar_icon;

    private static final ResourceLocation VK_MITE_ICONS = new ResourceLocation("textures/gui/MITE_icons.png");

     public InventoryEffectRendererGuiMixin(Container c) {
         super(c);
     }

     /**
      * @author fml-native
      * @reason native renderer: Draw colour/lighting instead of GL11
      */
     @Overwrite
    private void displayDebuffEffects() {
       int var1 = this.guiLeft - 128;
       int var2 = this.guiTop;
       boolean var3 = true;
       Collection var4 = this.mc.thePlayer.getActivePotionEffects();
       int num_effects = var4.size();
       if (this.mc.thePlayer.isMalnourished()) {
          num_effects++;
       }

       if (this.mc.thePlayer.isInsulinResistant()) {
          num_effects++;
       }

       if (this.mc.thePlayer.is_cursed) {
          num_effects++;
       }

       if (num_effects > 0) {
          Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
          Draw.lighting(false);
          int var5 = 33;
          if (num_effects > 5) {
             var5 = 132 / (num_effects - 1);
          }

          if (this.mc.thePlayer.isMalnourished()) {
             Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
             this.mc.getTextureManager().bindTexture(field_110408_a);
             this.drawTexturedModalRect(var1, var2, 0, 166, 140, 32);
             this.mc.getTextureManager().bindTexture(VK_MITE_ICONS);
             this.drawTexturedModalRect(var1 + 6, var2 + 7, 18, 198, 18, 18);
             String var11 = I18n.getString("effect.malnourished");
             this.fontRenderer.drawStringWithShadow(var11, var1 + 10 + 18 - 1, var2 + 6 + 1, 16777215);
             String var10 = ((int)this.mc.theWorld.getTotalWorldTime() - this.initial_tick) / 100 % 2 == 0
                ? I18n.getString("effect.malnourished.slowHealing")
                : I18n.getString("effect.malnourished.plus50PercentHunger");
             this.fontRenderer.drawStringWithShadow(var10, var1 + 10 + 18 - 1, var2 + 6 + 10 + 1, 8355711);
             var2 += var5;
          }

          if (this.mc.thePlayer.isInsulinResistant()) {
             Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
             this.mc.getTextureManager().bindTexture(field_110408_a);
             this.drawTexturedModalRect(var1, var2, 0, 166, 140, 32);
             this.mc.getTextureManager().bindTexture(sugar_icon);
             this.drawTexturedModalRect2(var1 + 7, var2 + 8, 16, 16);
             EnumInsulinResistanceLevel insulin_resistance_level = this.mc.thePlayer.getInsulinResistanceLevel();
             EnumChatFormatting irc = insulin_resistance_level == EnumInsulinResistanceLevel.mild ? EnumChatFormatting.YELLOW
                : insulin_resistance_level == EnumInsulinResistanceLevel.moderate ? EnumChatFormatting.GOLD : EnumChatFormatting.RED;
             Draw.color(irc.getRedAsFloat(), irc.getGreenAsFloat(), irc.getBlueAsFloat(), 1.0F);
             this.mc.getTextureManager().bindTexture(VK_MITE_ICONS);
             this.drawTexturedModalRect(var1 + 6, var2 + 7, 54, 198, 18, 18);
             Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
             String var11 = I18n.getString("effect.insulinResistance");
             this.fontRenderer.drawStringWithShadow(var11, var1 + 10 + 18 - 1, var2 + 6 + 1, 16777215);
             String var10 = StringUtils.ticksToElapsedTime(this.mc.thePlayer.getInsulinResistance());
             this.fontRenderer.drawStringWithShadow(var10, var1 + 10 + 18 - 1, var2 + 6 + 10 + 1, 8355711);
             var2 += var5;
          }

          if (this.mc.thePlayer.is_cursed) {
             Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
             this.mc.getTextureManager().bindTexture(field_110408_a);
             this.drawTexturedModalRect(var1, var2, 0, 166, 140, 32);
             this.mc.getTextureManager().bindTexture(VK_MITE_ICONS);
             this.drawTexturedModalRect(var1 + 6, var2 + 7, 0, 198, 18, 18);
             String var11 = I18n.getString("effect.cursed");
             this.fontRenderer.drawStringWithShadow(var11, var1 + 10 + 18 - 1, var2 + 6 + 1, 16777215);
             String var10 = this.mc.thePlayer.curse_effect_known
                ? EnumChatFormatting.DARK_PURPLE + this.mc.thePlayer.getCurse().getTitle()
                : Translator.get("curse.unknown");
             this.fontRenderer.drawStringWithShadow(var10, var1 + 10 + 18 - 1, var2 + 6 + 10 + 1, 8355711);
             var2 += var5;
          }

          for (Object var7o : this.mc.thePlayer.getActivePotionEffects()) {
             PotionEffect var7 = (PotionEffect) var7o;
            Potion var8 = Potion.potionTypes[var7.getPotionID()];
             Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
             this.mc.getTextureManager().bindTexture(field_110408_a);
             this.drawTexturedModalRect(var1, var2, 0, 166, 140, 32);
             if (var8.hasStatusIcon()) {
                int var9 = var8.getStatusIconIndex();
                this.drawTexturedModalRect(var1 + 6, var2 + 7, 0 + var9 % 8 * 18, 198 + var9 / 8 * 18, 18, 18);
             }

             String var11 = I18n.getString(var8.getName());
             if (var7.getAmplifier() == 1) {
                var11 = var11 + " II";
             } else if (var7.getAmplifier() == 2) {
                var11 = var11 + " III";
             } else if (var7.getAmplifier() == 3) {
                var11 = var11 + " IV";
             }

             this.fontRenderer.drawStringWithShadow(var11, var1 + 10 + 18 - 1, var2 + 6 + 1, 16777215);
             String var10 = Potion.getDurationString(var7);
             this.fontRenderer.drawStringWithShadow(var10, var1 + 10 + 18 - 1, var2 + 6 + 10 + 1, 8355711);
             var2 += var5;
          }
       }
    }
}
