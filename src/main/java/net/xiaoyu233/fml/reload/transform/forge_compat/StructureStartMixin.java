package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.gen.structure.MapGenStructureIO;
import net.minecraft.world.gen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureStart.class)
public abstract class StructureStartMixin {

    @Inject(method = "func_143021_a", at = @At("HEAD"))
    private void fmlForgeCheckId(int par1, int par2, CallbackInfoReturnable<net.minecraft.nbt.NBTTagCompound> cir) {
        if (MapGenStructureIO.func_143033_a((StructureStart)(Object)this) == null) {
            throw new RuntimeException("StructureStart \"" + this.getClass().getName() + "\" missing ID Mapping, Modder see MapGenStructureIO");
        }
    }
}
