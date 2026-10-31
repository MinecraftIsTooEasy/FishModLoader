package net.xiaoyu233.fml.vulkan.mixin.nat.particle;

import net.minecraft.Entity;
import net.minecraft.EntityFX;
import net.minecraft.EntityPickupFX;
import net.minecraft.RenderManager;
import net.minecraft.Tessellator;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "particle": item pickup FX with GL state routed through Draw. */
@Mixin(EntityPickupFX.class)
public abstract class EntityPickupFXParticleMixin extends EntityFX {
    @Shadow private Entity entityToPickUp;
    @Shadow private Entity entityPickingUp;
    @Shadow private int age;
    @Shadow private int maxAge;
    @Shadow private float yOffs;

    protected EntityPickupFXParticleMixin() {
        super(null, 0.0, 0.0, 0.0);
    }

    /**
     * @author NR-15
     * @reason native renderer: GL11/OpenGlHelper replaced by Draw.*
     */
    @Overwrite
    public void renderParticle(Tessellator tessellator, float partialTicks, float rotationX, float rotationZ, float rotationYZ, float rotationXY, float rotationXZ) {
        float progress = (this.age + partialTicks) / this.maxAge;
        progress *= progress;
        double pickupX = this.entityToPickUp.posX;
        double pickupY = this.entityToPickUp.posY;
        double pickupZ = this.entityToPickUp.posZ;
        double collectorX = this.entityPickingUp.lastTickPosX + (this.entityPickingUp.posX - this.entityPickingUp.lastTickPosX) * partialTicks;
        double collectorY = this.entityPickingUp.lastTickPosY + (this.entityPickingUp.posY - this.entityPickingUp.lastTickPosY) * partialTicks + this.yOffs;
        double collectorZ = this.entityPickingUp.lastTickPosZ + (this.entityPickingUp.posZ - this.entityPickingUp.lastTickPosZ) * partialTicks;
        double renderX = pickupX + (collectorX - pickupX) * progress;
        double renderY = pickupY + (collectorY - pickupY) * progress;
        double renderZ = pickupZ + (collectorZ - pickupZ) * progress;
        int brightness = this.getBrightnessForRender(partialTicks);
        float blockLight = brightness % 65536;
        float skyLight = brightness / 65536;
        Draw.light(blockLight, skyLight);
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
        renderX -= interpPosX;
        renderY -= interpPosY;
        renderZ -= interpPosZ;
        RenderManager.instance.renderEntityWithPosYaw(this.entityToPickUp, (float) renderX, (float) renderY, (float) renderZ, this.entityToPickUp.rotationYaw, partialTicks);
    }
}
