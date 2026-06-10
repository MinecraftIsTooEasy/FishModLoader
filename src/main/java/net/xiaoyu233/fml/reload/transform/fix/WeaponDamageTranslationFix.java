package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentDamage;
import net.minecraft.item.EnumRarity;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EnchantmentDamage.class)
public class WeaponDamageTranslationFix extends Enchantment {
   protected WeaponDamageTranslationFix(int id, EnumRarity rarity, int difficulty) {
      super(id, rarity, difficulty);
   }

   @Shadow
   public boolean canEnchantItem(Item item) {
      return false;
   }

   @Shadow
   public String getNameSuffix() {
      return null;
   }

   @Inject(method = "getTranslatedName", at = @At("HEAD"), cancellable = true)
   private void injectTranslateSlaying(Item item, CallbackInfoReturnable<String> callbackInfoReturnable) {
      if (this == Enchantment.sharpness && item instanceof ItemAxe) {
         callbackInfoReturnable.setReturnValue(I18n.getString("enchantment.slaying"));
      }
   }

   @Shadow
   public boolean isOnCreativeTab(CreativeTabs creativeModeTab) {
      return false;
   }
}
