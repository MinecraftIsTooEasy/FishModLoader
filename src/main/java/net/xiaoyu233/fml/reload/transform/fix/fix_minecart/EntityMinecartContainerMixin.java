package net.xiaoyu233.fml.reload.transform.fix.fix_minecart;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.item.EntityMinecartContainer;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityMinecartContainer.class)
public abstract class EntityMinecartContainerMixin extends EntityMinecart {
    public EntityMinecartContainerMixin(World par1World) {
        super(par1World);
    }

    @WrapOperation(method = "setDead", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/item/EntityMinecartContainer;dropContentsWhenDead:Z"))
    private boolean wrapContainerDropCondition(EntityMinecartContainer container, Operation<Boolean> booleanOperation){
        return booleanOperation.call(container).booleanValue() && !this.is_unwanted_duplicate;
    }
}
