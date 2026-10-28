package net.xiaoyu233.fml.vulkan.mixin.nat.special;

import net.minecraft.BossStatus;
import net.minecraft.EntityDragon;
import net.minecraft.EntityLiving;
import net.minecraft.EntityLivingBase;
import net.minecraft.MathHelper;
import net.minecraft.RenderDragon;
import net.minecraft.RenderHelper;
import net.minecraft.RenderLiving;
import net.xiaoyu233.fml.vulkan.render.special.BeamRenderer;
import net.xiaoyu233.fml.vulkan.render.state.AttribMask;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.DepthFunc;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Native area "special": dragon pose, dissolve passes, death beams and crystal beam on {@code Draw}. */
@Mixin(RenderDragon.class)
public abstract class RenderDragonSpecialMixin extends RenderLiving {
    private RenderDragonSpecialMixin() {
        super(null, 0.0F);
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    protected void rotateDragonBody(EntityDragon dragon, float f1, float f2, float partialTicks) {
        float yawOffset = (float) dragon.getMovementOffsets(7, partialTicks)[0];
        float pitchOffset = (float) (dragon.getMovementOffsets(5, partialTicks)[1] - dragon.getMovementOffsets(10, partialTicks)[1]);
        Draw.rotate(-yawOffset, 0.0F, 1.0F, 0.0F);
        Draw.rotate(pitchOffset * 10.0F, 1.0F, 0.0F, 0.0F);
        Draw.translate(0.0F, 0.0F, 1.0F);
        if (dragon.deathTime > 0) {
            float fall = (dragon.deathTime + partialTicks - 1.0F) / 20.0F * 1.6F;
            fall = MathHelper.sqrt_float(fall);
            if (fall > 1.0F) {
                fall = 1.0F;
            }

            Draw.rotate(fall * this.getDeathMaxRotation(dragon), 0.0F, 0.0F, 1.0F);
        }
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    protected void renderDragonModel(EntityDragon dragon, float f1, float f2, float f3, float f4, float f5, float f6) {
        boolean renderAsDying = dragon.deathTicks > 0;
        if (renderAsDying) {
            Draw.pushAttrib(AttribMask.decode(2932));
            float dissolve = dragon.deathTicks / 200.0F;
            Draw.depthFunc(DepthFunc.LEQUAL);
            Draw.alphaTest(true);
            Draw.alphaFunc(dissolve); // dynamic alpha ref (alpha mode 2)
            this.bindTexture(this.textures[2]);
            this.mainModel.render(dragon, f1, f2, f3, f4, f5, f6);
            Draw.alphaFunc(0.1F);
            Draw.depthFunc(DepthFunc.EQUAL);
        }

        this.bindEntityTexture(dragon);
        this.mainModel.render(dragon, f1, f2, f3, f4, f5, f6);
        this.renderModelGlowing(dragon, f1, f2, f3, f4, f5, f6);
        if (renderAsDying) {
            Draw.popAttrib();
        }

        if (dragon.hurtTime > 0) {
            Draw.depthFunc(DepthFunc.EQUAL);
            Draw.texturing(false);
            Draw.blend(true);
            Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
            Draw.color(1.0F, 0.0F, 0.0F, 0.5F);
            this.mainModel.render(dragon, f1, f2, f3, f4, f5, f6);
            Draw.texturing(true);
            Draw.blend(false);
            Draw.depthFunc(DepthFunc.LEQUAL);
        }
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    public void renderDragon(EntityDragon dragon, double x, double y, double z, float yaw, float partialTicks) {
        BossStatus.setBossStatus(dragon, false);
        super.doRenderLiving((EntityLiving) dragon, x, y, z, yaw, partialTicks);
        if (dragon.healingEnderCrystal != null) {
            float rotation = dragon.healingEnderCrystal.innerRotation + partialTicks;
            float bob = MathHelper.sin(rotation * 0.2F) / 2.0F + 0.5F;
            bob = (bob * bob + bob) * 0.2F;
            float dx = (float) (dragon.healingEnderCrystal.posX - dragon.posX - (dragon.prevPosX - dragon.posX) * (1.0F - partialTicks));
            float dy = (float) (
                bob + dragon.healingEnderCrystal.posY - 1.0 - dragon.posY - (dragon.prevPosY - dragon.posY) * (1.0F - partialTicks)
            );
            float dz = (float) (dragon.healingEnderCrystal.posZ - dragon.posZ - (dragon.prevPosZ - dragon.posZ) * (1.0F - partialTicks));
            float horizontal = MathHelper.sqrt_float(dx * dx + dz * dz);
            Draw.pushPose();
            Draw.translate((float) x, (float) y + 2.0F, (float) z);
            Draw.rotate((float) (-Math.atan2(dz, dx)) * 180.0F / (float) Math.PI - 90.0F, 0.0F, 1.0F, 0.0F);
            Draw.rotate((float) (-Math.atan2(horizontal, dy)) * 180.0F / (float) Math.PI - 90.0F, 1.0F, 0.0F, 0.0F);
            RenderHelper.disableStandardItemLighting();
            Draw.cull(false);
            this.bindTexture(this.textures[1]);
            Draw.smoothShading(true);
            BeamRenderer.dragonCrystalBeam(dx, dy, dz, partialTicks, dragon.ticksExisted);
            Draw.cull(true);
            Draw.smoothShading(false);
            RenderHelper.enableStandardItemLighting();
            Draw.popPose();
        }
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    protected void renderDragonDying(EntityDragon dragon, float partialTicks) {
        super.renderEquippedItems((EntityLivingBase) dragon, partialTicks);
        if (dragon.deathTicks > 0) {
            BeamRenderer.dragonDeathBeams(dragon.deathTicks, partialTicks);
        }
    }
}
