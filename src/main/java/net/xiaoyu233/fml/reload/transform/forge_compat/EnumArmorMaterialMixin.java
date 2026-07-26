package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.item.EnumArmorMaterial;
import net.minecraft.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EnumArmorMaterial.class)
public class EnumArmorMaterialMixin {
    @Unique
    public Item customCraftingMaterial = null;

    /**
     * @reason Forge adds support for custom crafting materials
     */
    @Overwrite
    public int getArmorCraftingMaterial() {
        switch ((EnumArmorMaterial)(Object)this) {
            case CLOTH:   return Item.leather.itemID;
            case CHAIN:   return Item.ingotIron.itemID;
            case GOLD:    return Item.ingotGold.itemID;
            case IRON:    return Item.ingotIron.itemID;
            case DIAMOND: return Item.diamond.itemID;
            default:      return (customCraftingMaterial == null ? 0 : customCraftingMaterial.itemID);
        }
    }
}
