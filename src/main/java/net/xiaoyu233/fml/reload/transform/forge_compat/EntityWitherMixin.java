package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityWither.class)
public class EntityWitherMixin {
    @Redirect(method = "func_82206_b", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;setBlockToAir(III)Z"))
    private boolean fmlForgeCanEntityDestroy(World world, int x, int y, int z) {
        return world.setBlockToAir(x, y, z);
    }
}
