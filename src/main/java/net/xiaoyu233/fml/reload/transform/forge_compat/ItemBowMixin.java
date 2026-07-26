package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ArrowNockEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

@Mixin(ItemBow.class)
public abstract class ItemBowMixin {

    @Shadow
    public abstract int getMaxItemUseDuration(ItemStack par1ItemStack);

    @Shadow
    private Random itemRand;

    /**
     * Injects at HEAD of onItemRightClick to fire ArrowNockEvent.
     */
    @Inject(method = "onItemRightClick(Lnet/minecraft/entity/player/EntityPlayer;FZ)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void fmlForgeOnItemRightClickBow(EntityPlayer par3EntityPlayer, float par2, boolean par3,
                                              CallbackInfoReturnable<Boolean> cir) {
        ArrowNockEvent event = new ArrowNockEvent(par3EntityPlayer, par3EntityPlayer.getHeldItemStack());
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            cir.setReturnValue(true);
        }
    }
}
