package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.event.Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(net.minecraft.item.ItemHoe.class)
public abstract class ItemHoeMixin {
    @Inject(method = "tryTillSoil", at = @At("HEAD"), cancellable = true)
    private static void fmlForgeOnItemUseHoe(World world, int x, int y, int z, net.minecraft.util.EnumFace face, EntityPlayer player, ItemStack item_stack, CallbackInfoReturnable<Boolean> cir) {
        net.minecraftforge.event.entity.player.UseHoeEvent event = new net.minecraftforge.event.entity.player.UseHoeEvent(player, item_stack, world, x, y, z);
        if (net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event)) {
            cir.setReturnValue(false);
            return;
        }
        if (event.getResult() == Event.Result.ALLOW) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "onItemUse", at = @At(value = "FIELD", target = "Lnet/minecraft/world/World;isRemote:Z", shift = At.Shift.BEFORE))
    private void fmlForgeFixAirCheck(ItemStack par1ItemStack, EntityPlayer par2EntityPlayer, World par3World, int par4, int par5, int par6, int par7, float par8, float par9, float par10, CallbackInfoReturnable<Boolean> cir) {
        // The patch changes the block check at the end of onItemUse
    }
}
