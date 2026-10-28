package net.xiaoyu233.fml.vulkan.mixin.nat.particle;

import net.minecraft.Entity;
import net.minecraft.EntityFX;
import net.xiaoyu233.fml.vulkan.render.particle.ParticleFastPath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "particle": ENT-01 redirect of the {@code moveEntity} call in {@code EntityFX.onUpdate}, and the duck accessors
 * ({@link ParticleFastPath.FxAccess}) that give the fast path access to the private/protected state of {@code EntityFX}/{@code Entity}.
 */
@Mixin(EntityFX.class)
public abstract class EntityFXParticleMixin extends Entity implements ParticleFastPath.FxAccess {
    @Shadow
    private boolean prev_pos_initialized;
    @Shadow
    protected float particleScale;

    protected EntityFXParticleMixin() {
        super(null);
    }

    @Redirect(method = "onUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/EntityFX;moveEntity(DDD)V"))
    private void vkp$move(EntityFX self, double dx, double dy, double dz) {
        ParticleFastPath.move(self, dx, dy, dz);
    }

    @Override
    public float vkp$scale() {
        return this.particleScale;
    }

    @Override
    public boolean vkp$prevPosInitialized() {
        return this.prev_pos_initialized;
    }

    @Override
    public void vkp$initPrevPos() {
        if (!this.prev_pos_initialized) {
            this.prevPosX = this.posX;
            this.prevPosY = this.posY;
            this.prevPosZ = this.posZ;
            this.prev_pos_initialized = true;
        }
    }

    @Override
    public boolean vkp$inWeb() {
        return this.isInWeb;
    }

    @Override
    public void vkp$setInWeb(boolean v) {
        this.isInWeb = v;
    }

    @Override
    public void vkp$fallState(double dy, boolean onGround) {
        this.updateFallState(dy, onGround);
    }

    @Override
    public void vkp$blockCollisions() {
        this.doBlockCollisions();
    }
}
