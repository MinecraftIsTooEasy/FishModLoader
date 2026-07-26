package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(net.minecraft.world.Explosion.class)
public abstract class ExplosionMixin {

    @Unique
    public Entity exploder;

    @Unique
    public World worldObj;

    @Unique
    public double explosionX;
    @Unique
    public double explosionY;
    @Unique
    public double explosionZ;

    @Unique
    public float explosionSize;
}
