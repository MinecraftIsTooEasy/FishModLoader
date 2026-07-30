package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFire;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeDirection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static net.minecraftforge.common.ForgeDirection.DOWN;
import static net.minecraftforge.common.ForgeDirection.EAST;
import static net.minecraftforge.common.ForgeDirection.NORTH;
import static net.minecraftforge.common.ForgeDirection.SOUTH;
import static net.minecraftforge.common.ForgeDirection.UP;
import static net.minecraftforge.common.ForgeDirection.WEST;

@Mixin(BlockFire.class)
public class BlockFireMixin {
    @Shadow
    private int[] chanceToEncourageFire;

    @Shadow
    private int[] abilityToCatchFire;

    @Inject(method = "canBlockCatchFire(Lnet/minecraft/world/IBlockAccess;III)Z", at = @At("HEAD"), cancellable = true)
    private void onCanBlockCatchFire(IBlockAccess world, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.canBlockCatchFire(world, x, y, z, UP));
    }

    @Inject(method = "getChanceOfNeighborsEncouragingFire", at = @At("HEAD"), cancellable = true)
    private void onGetChanceOfNeighborsEncouragingFire(World world, int x, int y, int z, CallbackInfoReturnable<Integer> cir) {
        int current = 0;
        if (!world.isAirBlock(x, y, z)) {
            cir.setReturnValue(0);
            return;
        }
        current = this.getChanceToEncourageFire(world, x + 1, y, z, current, WEST);
        current = this.getChanceToEncourageFire(world, x - 1, y, z, current, EAST);
        current = this.getChanceToEncourageFire(world, x, y - 1, z, current, UP);
        current = this.getChanceToEncourageFire(world, x, y + 1, z, current, DOWN);
        current = this.getChanceToEncourageFire(world, x, y, z - 1, current, SOUTH);
        current = this.getChanceToEncourageFire(world, x, y, z + 1, current, NORTH);
        cir.setReturnValue(current);
    }

    // ========== @Unique: New ForgeDirection overloads ==========

    /**
     * Side-sensitive version of canBlockCatchFire that checks the specified face.
     */
    @Unique
    public boolean canBlockCatchFire(IBlockAccess world, int x, int y, int z, ForgeDirection face) {
        Block block = Block.blocksList[world.getBlockId(x, y, z)];
        if (block != null) {
            return block.blockID == Block.netherrack.blockID || this.abilityToCatchFire[block.blockID] > 0;
        }
        return false;
    }

    /**
     * Side-sensitive version of getChanceToEncourageFire that checks the specified face.
     *
     * @param world     The current world
     * @param x         X Position
     * @param y         Y Position
     * @param z         Z Position
     * @param oldChance The previous maximum chance
     * @param face      The side the fire is coming from
     * @return The chance of the block catching fire, or oldChance if it is higher
     */
    @Unique
    public int getChanceToEncourageFire(World world, int x, int y, int z, int oldChance, ForgeDirection face) {
        int newChance = 0;
        Block block = Block.blocksList[world.getBlockId(x, y, z)];
        if (block != null) {
            newChance = this.chanceToEncourageFire[block.blockID];
        }
        return Math.max(newChance, oldChance);
    }
}
