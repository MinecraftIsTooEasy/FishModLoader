package net.xiaoyu233.fml.reload.transform.id_extend;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBreakInfo;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFace;
import net.minecraft.network.SignalData;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Block.class)
public class BlockMixin {
    @Shadow @Final public int blockID;

    @ModifyConstant(method = {"<clinit>", "getBlock(Ljava/lang/String;)Lnet/minecraft/block/Block;",}, constant = @Constant(intValue = 256))
    private static int modifyMaxId(int value) {
        return 32000;
    }

    @ModifyConstant(method = {"<clinit>", "getBlock(Ljava/lang/String;)Lnet/minecraft/block/Block;",}, constant = @Constant(intValue = 4096))
    private static int modifyMaxId2(int value) {
        return 32000;
    }

    @Redirect(method = "tryPlaceBlock(Lnet/minecraft/world/World;IIILnet/minecraft/util/EnumFace;ILnet/minecraft/entity/Entity;ZZZ)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/SignalData;setInteger(I)Lnet/minecraft/network/SignalData;"))
    private SignalData redirectSetBreakSignalData(SignalData data, int value, World world, int x, int y, int z, EnumFace face, int metadata, Entity placer, boolean perform_placement_check, boolean drop_existing_block, boolean test_only, @Local Block existing_block, @Local BlockBreakInfo info){
        return data.setInteger(existing_block.blockID + (info.getMetadata() << 12) + (this.blockID << 16) + (metadata << 28));
    }
}
