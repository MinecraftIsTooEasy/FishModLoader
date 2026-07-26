package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityBrewingStand;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.brewing.PotionBrewedEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TileEntityBrewingStand.class)
public abstract class TileEntityBrewingStandMixin {

    @Shadow
    private ItemStack[] brewingItemStacks;

    /**
     * Injects PotionBrewedEvent after brewing completes.
     */
    @Inject(method = "updateEntity", at = @At("TAIL"))
    private void fmlForgeOnBrewingUpdate(CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new PotionBrewedEvent(this.brewingItemStacks));
    }
}
