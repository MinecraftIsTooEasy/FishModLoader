package net.xiaoyu233.fml.reload.transform.forge_compat.api;

import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.gen.MapGenBase")
public interface IMapGenBaseAccessor {
    @Accessor("worldObj")
    World fmlGetWorld();
}
