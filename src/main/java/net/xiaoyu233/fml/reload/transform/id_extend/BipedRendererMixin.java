package net.xiaoyu233.fml.reload.transform.id_extend;

import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.client.renderer.entity.RenderBiped;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(RenderBiped.class)
public class BipedRendererMixin {
    @Redirect(method = "func_130005_c", at = @At(value = "FIELD", target = "Lnet/minecraft/item/ItemStack;itemID:I", ordinal = 0), slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/tileentity/TileEntitySkullRenderer;func_82393_a(FFFIFILjava/lang/String;)V"),to = @At(value = "CONSTANT", args = "classValue=net/minecraft/item/ItemBow")))
    private int redirectGetItemId(ItemStack stack){
        if (stack.getItem() instanceof ItemBlock) return 0;
        return stack.itemID;
    }

    @Redirect(method = "func_130005_c", at = @At(value = "FIELD", target = "Lnet/minecraft/item/Item;itemID:I"), slice = @Slice(to = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderBlocks;renderItemIn3d(I)Z", ordinal = 0)))
    private int redirectGetItemId1(Item item){
        if (item instanceof ItemBlock) return 0;
        return item.itemID;
    }
}
