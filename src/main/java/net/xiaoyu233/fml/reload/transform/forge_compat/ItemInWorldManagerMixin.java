package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBreakInfo;
import net.minecraft.item.ItemInWorldManager;
import net.xiaoyu233.fml.modfixer.LegacyForgeBlockHarvestBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Routes MITE's player-drop calls through legacy Forge Block.harvestBlock overrides. */
@Mixin(ItemInWorldManager.class)
public abstract class ItemInWorldManagerMixin {
    @Redirect(
            method = "tryHarvestBlock(III)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/block/Block;dropBlockAsItself(Lnet/minecraft/block/BlockBreakInfo;)I")
    )
    private int fmlForgeLegacySilkHarvest(Block block, BlockBreakInfo info) {
        if (LegacyForgeBlockHarvestBridge.invokeLegacyHarvest(block, info)) return 0;
        return block.dropBlockAsItself(info);
    }

    @Redirect(
            method = "tryHarvestBlock(III)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/block/Block;dropBlockAsEntityItem(Lnet/minecraft/block/BlockBreakInfo;)I")
    )
    private int fmlForgeLegacyHarvest(Block block, BlockBreakInfo info) {
        if (LegacyForgeBlockHarvestBridge.invokeLegacyHarvest(block, info)) return 0;
        return block.dropBlockAsEntityItem(info);
    }
}
