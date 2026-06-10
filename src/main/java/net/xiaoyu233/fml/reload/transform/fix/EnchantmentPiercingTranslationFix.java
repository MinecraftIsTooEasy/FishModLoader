package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentPiercing;
import net.minecraft.item.EnumRarity;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EnchantmentPiercing.class)
public abstract class EnchantmentPiercingTranslationFix extends Enchantment {

   protected EnchantmentPiercingTranslationFix(int id, EnumRarity rarity, int difficulty) {
      super(id, rarity, difficulty);
   }

   @Shadow
   public boolean canEnchantItem(Item var1) {
      return false;
   }


   @Shadow
   public String getNameSuffix() {
      return null;
   }

   @Inject(method = "getTranslatedName", at = @At("HEAD"), cancellable = true)
   private void injectTranslateCleaving(Item item, CallbackInfoReturnable<String> callbackInfoReturnable) {
      if (item instanceof ItemAxe) {
         callbackInfoReturnable.setReturnValue(I18n.getString("enchantment.cleaving"));
      }
   }

   @Shadow
   public boolean isOnCreativeTab(CreativeTabs var1) {
      return false;
   }
}
