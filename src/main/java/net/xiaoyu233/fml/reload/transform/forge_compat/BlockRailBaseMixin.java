package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockRailBase;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockRailBase.class)
public abstract class BlockRailBaseMixin {
    @Shadow
    @Final
    public boolean isPowered;

    @Unique
    public boolean isFlexibleRail(World world, int y, int x, int z) {
        return !isPowered;
    }

    @Unique
    public boolean canMakeSlopes(World world, int x, int y, int z) {
        return true;
    }

    @Unique
    public int getBasicRailMetadata(IBlockAccess world, EntityMinecart cart, int x, int y, int z) {
        int meta = world.getBlockMetadata(x, y, z);
        if (isPowered) {
            meta = meta & 7;
        }
        return meta;
    }

    @Unique
    public float getRailMaxSpeed(World world, EntityMinecart cart, int y, int x, int z) {
        return 0.4f;
    }

    @Unique
    public void onMinecartPass(World world, EntityMinecart cart, int y, int x, int z) {
    }

    @Unique
    private int renderType = 9;

    @Unique
    public void setRenderType(int value) {
        renderType = value;
    }
}
