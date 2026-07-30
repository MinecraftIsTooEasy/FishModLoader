package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityStruckByLightningEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityLightningBolt.class)
public class EntityLightningBoltMixin {
    @Redirect(method = "onUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;onStruckByLightning(Lnet/minecraft/entity/effect/EntityLightningBolt;)V"))
    private void fmlForgeOnStruckByLightning(Entity entity, EntityLightningBolt bolt) {
        if (!MinecraftForge.EVENT_BUS.post(new EntityStruckByLightningEvent(entity, bolt))) {
            entity.onStruckByLightning(bolt);
        }
    }
}
