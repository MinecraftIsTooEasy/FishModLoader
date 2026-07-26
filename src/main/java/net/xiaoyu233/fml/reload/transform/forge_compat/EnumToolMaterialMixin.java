package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.item.EnumToolMaterial;
import net.minecraft.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EnumToolMaterial.class)
public class EnumToolMaterialMixin {
    @Unique
    public Item customCraftingMaterial = null;

    /**
     * @reason Forge adds support for custom crafting materials
     */
    @Overwrite
    public int getToolCraftingMaterial() {
        switch ((EnumToolMaterial)(Object)this) {
            case WOOD:    return Block.planks.blockID;
            case STONE:   return Block.cobblestone.blockID;
            case GOLD:    return Item.ingotGold.itemID;
            case IRON:    return Item.ingotIron.itemID;
            case EMERALD: return Item.diamond.itemID;
            default:      return (customCraftingMaterial == null ? 0 : customCraftingMaterial.itemID);
        }
    }
}
