package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.tileentity.TileEntityBeacon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(TileEntityBeacon.class)
public abstract class TileEntityBeaconMixin {

    @Shadow
    protected net.minecraft.world.World worldObj;

    @Shadow
    protected int xCoord;

    @Shadow
    protected int yCoord;

    @Shadow
    protected int zCoord;
}
