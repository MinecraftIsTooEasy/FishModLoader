package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.gen.structure.MapGenStructureIO;
import net.minecraft.world.gen.structure.StructureComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureComponent.class)
public abstract class StructureComponentMixin {

    @Inject(method = "func_143010_b", at = @At("HEAD"))
    private void fmlForgeCheckId(CallbackInfoReturnable<NBTTagCompound> cir) {
        if (MapGenStructureIO.func_143036_a((StructureComponent)(Object)this) == null) {
            throw new RuntimeException("StructureComponent \"" + this.getClass().getName() + "\" missing ID Mapping, Modder see MapGenStructureIO");
        }
    }
}
