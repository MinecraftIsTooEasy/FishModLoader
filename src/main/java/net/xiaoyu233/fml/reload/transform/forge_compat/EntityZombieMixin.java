package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.ForgeDummyContainer;
import net.minecraftforge.event.ForgeEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityZombie.class)
public class EntityZombieMixin {
    @ModifyConstant(method = "<init>(Lnet/minecraft/world/World;)V", constant = @Constant(doubleValue = 0.10000000149011612D))
    private double fmlForgeZombieSummonBaseChance(double original) {
        return ForgeDummyContainer.zombieSummonBaseChance;
    }

    @ModifyConstant(method = "onSpawnWithEgg", constant = @Constant(floatValue = 0.05F, ordinal = 0))
    private float fmlForgeZombieBabyChance(float original) {
        return (float) ForgeDummyContainer.zombieBabyChance;
    }
}
