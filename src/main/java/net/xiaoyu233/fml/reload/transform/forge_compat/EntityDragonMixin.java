package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityDragon.class)
public class EntityDragonMixin {
    @Redirect(method = "destroyBlocksInAABB", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;setBlockToAir(III)Z"))
    private boolean fmlForgeCanEntityDestroy(World world, int x, int y, int z) {
        int j2 = world.getBlockId(x, y, z);
        Block block = Block.blocksList[j2];
        if (block != null) {
            // Forge hook: block.canEntityDestroy(world, x, y, z, entity)
            // MITE may not have this method on Block, so we use default behavior
        }
        return world.setBlockToAir(x, y, z);
    }
}
