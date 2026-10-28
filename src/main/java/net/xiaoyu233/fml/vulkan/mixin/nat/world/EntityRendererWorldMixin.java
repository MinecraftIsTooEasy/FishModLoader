package net.xiaoyu233.fml.vulkan.mixin.nat.world;

import net.minecraft.*;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Frame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Native area "world" (NR-12), camera part: hurtCameraEffect, setupViewBobbing, orientCamera, setupCameraTransform, renderHand,
 * enableLightmap, disableLightmap, setupOverlayRendering. Operation order is identical to MITE (glMatrixMode 5889 -> Draw.projection(),
 * 5888 -> Draw.pose(), 5890 -> Draw.uvMatrix(); gluPerspective -> PoseStack.perspective). Constructor needs no change (DynamicTexture is
 * backed natively). Open end-state item: the static initialiser of {@code capability_gl_nv_fog_distance} (<clinit>) still calls
 * {@code GLContext.getCapabilities()}. No mixin in this package redirects it, because the only readers of the field (MITE setupFog)
 * are overwritten by {@link EntityRendererFogWorldMixin}, so the field is dead after the native fog rewrite. A type-free redirect
 * is not possible without naming {@code org.lwjgl.opengl.ContextCapabilities} in native code (§0.3), so the call needs a contract
 * decision (NR-28 scan whitelist for this one site, or an explicit owner) before the zero-site gate of §7.1 can pass.
 * Other EntityRenderer methods belong to {@link EntityRendererFrameWorldMixin} and {@link EntityRendererFogWorldMixin}.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererWorldMixin {
    @Shadow private Minecraft mc;
    @Shadow private float farPlaneDistance;
    @Shadow public ItemRenderer itemRenderer;
    @Shadow private int rendererUpdateCount;
    @Shadow private float thirdPersonDistance;
    @Shadow private float thirdPersonDistanceTemp;
    @Shadow private float debugCamYaw;
    @Shadow private float prevDebugCamYaw;
    @Shadow private float debugCamPitch;
    @Shadow private float prevDebugCamPitch;
    @Shadow private float camRoll;
    @Shadow private float prevCamRoll;
    @Shadow @Final private ResourceLocation locationLightMap;
    @Shadow private boolean cloudFog;
    @Shadow private double cameraZoom;
    @Shadow private double cameraYaw;
    @Shadow private double cameraPitch;
    @Shadow public int debugViewDirection;

    @Shadow private float getFOVModifier(float par1, boolean par2) { throw new AssertionError(); }

    /** @author FML-Vulkan NR-12 @reason native hurtCameraEffect (Draw/Frame API) */
    @Overwrite
     private void hurtCameraEffect(float par1) {
        EntityLivingBase var2 = this.mc.renderViewEntity;
        float var3 = var2.hurtTime - par1;
        if (var2.getHealth() <= 0.0F) {
           float var4 = var2.deathTime + par1;
           Draw.pose().rotate(40.0F - 8000.0F / (var4 + 200.0F), 0.0F, 0.0F, 1.0F);
        }

        if (var3 >= 0.0F) {
           var3 /= var2.maxHurtTime;
           var3 = MathHelper.sin(var3 * var3 * var3 * var3 * (float) Math.PI);
           float var4 = var2.attackedAtYaw;
           Draw.pose().rotate(-var4, 0.0F, 1.0F, 0.0F);
           Draw.pose().rotate(-var3 * 14.0F, 0.0F, 0.0F, 1.0F);
           Draw.pose().rotate(var4, 0.0F, 1.0F, 0.0F);
        }
     }

    /** @author FML-Vulkan NR-12 @reason native setupViewBobbing (Draw/Frame API) */
    @Overwrite
     private void setupViewBobbing(float par1) {
        if (this.mc.renderViewEntity instanceof EntityPlayer) {
           EntityPlayer var2 = (EntityPlayer)this.mc.renderViewEntity;
           float var3 = var2.distanceWalkedModified - var2.prevDistanceWalkedModified;
           float var4 = -(var2.distanceWalkedModified + var3 * par1);
           float var5 = var2.prevCameraYaw + (var2.cameraYaw - var2.prevCameraYaw) * par1;
           float var6 = var2.prevCameraPitch + (var2.cameraPitch - var2.prevCameraPitch) * par1;
           Draw.pose().translate(MathHelper.sin(var4 * (float) Math.PI) * var5 * 0.5F, -Math.abs(MathHelper.cos(var4 * (float) Math.PI) * var5), 0.0F);
           Draw.pose().rotate(MathHelper.sin(var4 * (float) Math.PI) * var5 * 3.0F, 0.0F, 0.0F, 1.0F);
           Draw.pose().rotate(Math.abs(MathHelper.cos(var4 * (float) Math.PI - 0.2F) * var5) * 5.0F, 1.0F, 0.0F, 0.0F);
           Draw.pose().rotate(var6, 1.0F, 0.0F, 0.0F);
        }
     }

    /** @author FML-Vulkan NR-12 @reason native orientCamera (Draw/Frame API) */
    @Overwrite
     private void orientCamera(float par1) {
        EntityLivingBase var2 = this.mc.renderViewEntity;
        float var3 = var2.yOffset - 1.62F;
        if (this.mc.thePlayer.isSneaking()) {
           var3 -= -0.2385F;
        }

        double var4 = var2.prevPosX + (var2.posX - var2.prevPosX) * par1;
        double var6 = var2.prevPosY + (var2.posY - var2.prevPosY) * par1 - var3;
        double var8 = var2.prevPosZ + (var2.posZ - var2.prevPosZ) * par1;
        Draw.pose().rotate(this.prevCamRoll + (this.camRoll - this.prevCamRoll) * par1, 0.0F, 0.0F, 1.0F);
        if (var2.inBed()) {
           var3 = (float)(var3 + 1.0);
           Draw.pose().translate(0.0F, 0.3F, 0.0F);
           if (!this.mc.gameSettings.debugCamEnable) {
              int var10 = this.mc.theWorld.getBlockId(MathHelper.floor_double(var2.posX), MathHelper.floor_double(var2.posY), MathHelper.floor_double(var2.posZ));
              if (var10 == Block.bed.blockID) {
                 int var11 = this.mc
                    .theWorld
                    .getBlockMetadata(MathHelper.floor_double(var2.posX), MathHelper.floor_double(var2.posY), MathHelper.floor_double(var2.posZ));
                 int var12 = var11 & 3;
                 Draw.pose().rotate(var12 * 90, 0.0F, 1.0F, 0.0F);
              }

              Draw.pose().rotate(var2.prevRotationYaw + (var2.rotationYaw - var2.prevRotationYaw) * par1 + 180.0F, 0.0F, -1.0F, 0.0F);
              Draw.pose().rotate(var2.prevRotationPitch + (var2.rotationPitch - var2.prevRotationPitch) * par1, -1.0F, 0.0F, 0.0F);
           }
        } else if (this.mc.gameSettings.thirdPersonView > 0) {
           double var27 = this.thirdPersonDistanceTemp + (this.thirdPersonDistance - this.thirdPersonDistanceTemp) * par1;
           if (this.mc.gameSettings.debugCamEnable) {
              float var28 = this.prevDebugCamYaw + (this.debugCamYaw - this.prevDebugCamYaw) * par1;
              float var13 = this.prevDebugCamPitch + (this.debugCamPitch - this.prevDebugCamPitch) * par1;
              Draw.pose().translate(0.0F, 0.0F, (float)(-var27));
              Draw.pose().rotate(var13, 1.0F, 0.0F, 0.0F);
              Draw.pose().rotate(var28, 0.0F, 1.0F, 0.0F);
           } else {
              float var28 = var2.rotationYaw;
              float var13 = var2.rotationPitch;
              if (this.mc.gameSettings.thirdPersonView == 2) {
                 var13 += 180.0F;
              }

              double var14 = -MathHelper.sin(var28 / 180.0F * (float) Math.PI) * MathHelper.cos(var13 / 180.0F * (float) Math.PI) * var27;
              double var16 = MathHelper.cos(var28 / 180.0F * (float) Math.PI) * MathHelper.cos(var13 / 180.0F * (float) Math.PI) * var27;
              double var18 = -MathHelper.sin(var13 / 180.0F * (float) Math.PI) * var27;

              for (int var20 = 0; var20 < 8; var20++) {
                 float var21 = (var20 & 1) * 2 - 1;
                 float var22 = (var20 >> 1 & 1) * 2 - 1;
                 float var23 = (var20 >> 2 & 1) * 2 - 1;
                 var21 *= 0.1F;
                 var22 *= 0.1F;
                 var23 *= 0.1F;
                 RaycastCollision var24 = this.mc
                    .theWorld
                    .getBlockCollisionForPolicies(
                       this.mc.theWorld.getVec3(var4 + var21, var6 + var22, var8 + var23),
                       this.mc.theWorld.getVec3(var4 - var14 + var21 + var23, var6 - var18 + var22, var8 - var16 + var23),
                       RaycastPolicies.for_third_person_view
                    );
                 if (var24 != null) {
                    double var25 = var24.position_hit.distanceTo(this.mc.theWorld.getWorldVec3Pool().getVecFromPool(var4, var6, var8));
                    if (var25 < var27) {
                       var27 = var25;
                    }
                 }
              }

              if (this.mc.gameSettings.thirdPersonView == 2) {
                 Draw.pose().rotate(180.0F, 0.0F, 1.0F, 0.0F);
              }

              Draw.pose().rotate(var2.rotationPitch - var13, 1.0F, 0.0F, 0.0F);
              Draw.pose().rotate(var2.rotationYaw - var28, 0.0F, 1.0F, 0.0F);
              Draw.pose().translate(0.0F, 0.0F, (float)(-var27));
              Draw.pose().rotate(var28 - var2.rotationYaw, 0.0F, 1.0F, 0.0F);
              Draw.pose().rotate(var13 - var2.rotationPitch, 1.0F, 0.0F, 0.0F);
           }
        } else {
           Draw.pose().translate(0.0F, 0.0F, -0.1F);
        }

        if (!this.mc.gameSettings.debugCamEnable) {
           Draw.pose().rotate(var2.prevRotationPitch + (var2.rotationPitch - var2.prevRotationPitch) * par1, 1.0F, 0.0F, 0.0F);
           Draw.pose().rotate(var2.prevRotationYaw + (var2.rotationYaw - var2.prevRotationYaw) * par1 + 180.0F, 0.0F, 1.0F, 0.0F);
        }

        Draw.pose().translate(0.0F, var3, 0.0F);
        var4 = var2.prevPosX + (var2.posX - var2.prevPosX) * par1;
        var6 = var2.prevPosY + (var2.posY - var2.prevPosY) * par1 - var3;
        var8 = var2.prevPosZ + (var2.posZ - var2.prevPosZ) * par1;
        this.cloudFog = this.mc.renderGlobal.hasCloudFog(var4, var6, var8, par1);
     }

    /** @author FML-Vulkan NR-12 @reason native setupCameraTransform (Draw/Frame API) */
    @Overwrite
     public void setupCameraTransform(float par1, int par2, boolean extend_far_clipping_plane) {
        this.farPlaneDistance = extend_far_clipping_plane ? 384 : 256 >> this.mc.gameSettings.getRenderDistance();
        Draw.projection().loadIdentity();
        float var3 = 0.07F;
        if (this.mc.gameSettings.anaglyph) {
           Draw.projection().translate(-(par2 * 2 - 1) * var3, 0.0F, 0.0F);
        }

        if (this.cameraZoom != 1.0) {
           Draw.projection().translate((float)this.cameraYaw, (float)(-this.cameraPitch), 0.0F);
           Draw.projection().scale(this.cameraZoom, this.cameraZoom, 1.0);
        }

        Draw.projection().perspective(this.getFOVModifier(par1, true), (float)this.mc.displayWidth / this.mc.displayHeight, 0.05F, this.farPlaneDistance * 2.0F);
        if (this.mc.playerController.enableEverythingIsScrewedUpMode()) {
           float var4 = 0.6666667F;
           Draw.projection().scale(1.0F, var4, 1.0F);
        }

        Draw.pose().loadIdentity();
        if (this.mc.gameSettings.anaglyph) {
           Draw.pose().translate((par2 * 2 - 1) * 0.1F, 0.0F, 0.0F);
        }

        this.hurtCameraEffect(par1);
        if (this.mc.gameSettings.viewBobbing) {
           this.setupViewBobbing(par1);
        }

        float var4 = this.mc.thePlayer.prevTimeInPortal + (this.mc.thePlayer.timeInPortal - this.mc.thePlayer.prevTimeInPortal) * par1;
        if (var4 > 0.0F) {
           byte var5 = 20;
           if (this.mc.thePlayer.isPotionActive(Potion.confusion) && this.mc.thePlayer.getActivePotionEffect(Potion.confusion).getAmplifier() > 0) {
              var5 = 7;
              var4 *= 0.25F * this.mc.thePlayer.getActivePotionEffect(Potion.confusion).getAmplifier();
           }

           float var6 = 5.0F / (var4 * var4 + 5.0F) - var4 * 0.04F;
           var6 *= var6;
           Draw.pose().rotate((this.rendererUpdateCount + par1) * var5, 0.0F, 1.0F, 1.0F);
           Draw.pose().scale(1.0F / var6, 1.0F, 1.0F);
           Draw.pose().rotate(-(this.rendererUpdateCount + par1) * var5, 0.0F, 1.0F, 1.0F);
        }

        this.orientCamera(par1);
        if (this.debugViewDirection > 0) {
           int var7 = this.debugViewDirection - 1;
           if (var7 == 1) {
              Draw.pose().rotate(90.0F, 0.0F, 1.0F, 0.0F);
           }

           if (var7 == 2) {
              Draw.pose().rotate(180.0F, 0.0F, 1.0F, 0.0F);
           }

           if (var7 == 3) {
              Draw.pose().rotate(-90.0F, 0.0F, 1.0F, 0.0F);
           }

           if (var7 == 4) {
              Draw.pose().rotate(90.0F, 1.0F, 0.0F, 0.0F);
           }

           if (var7 == 5) {
              Draw.pose().rotate(-90.0F, 1.0F, 0.0F, 0.0F);
           }
        }
     }

    /** @author FML-Vulkan NR-12 @reason native renderHand (Draw/Frame API) */
    @Overwrite
     private void renderHand(float par1, int par2) {
        if (this.debugViewDirection <= 0) {
           Draw.projection().loadIdentity();
           float var3 = 0.07F;
           if (this.mc.gameSettings.anaglyph) {
              Draw.projection().translate(-(par2 * 2 - 1) * var3, 0.0F, 0.0F);
           }

           if (this.cameraZoom != 1.0) {
              Draw.projection().translate((float)this.cameraYaw, (float)(-this.cameraPitch), 0.0F);
              Draw.projection().scale(this.cameraZoom, this.cameraZoom, 1.0);
           }

           Draw.projection().perspective(this.getFOVModifier(par1, false), (float)this.mc.displayWidth / this.mc.displayHeight, 0.05F, this.farPlaneDistance * 2.0F);
           if (this.mc.playerController.enableEverythingIsScrewedUpMode()) {
              float var4 = 0.6666667F;
              Draw.projection().scale(1.0F, var4, 1.0F);
           }

           Draw.pose().loadIdentity();
           if (this.mc.gameSettings.anaglyph) {
              Draw.pose().translate((par2 * 2 - 1) * 0.1F, 0.0F, 0.0F);
           }

           Draw.pushPose();
           this.hurtCameraEffect(par1);
           if (this.mc.gameSettings.viewBobbing) {
              this.setupViewBobbing(par1);
           }

           if (this.mc.gameSettings.thirdPersonView == 0
              && !this.mc.renderViewEntity.inBed()
              && this.mc.gameSettings.gui_mode != 2
              && !this.mc.playerController.enableEverythingIsScrewedUpMode()) {
              this.enableLightmap(par1);
              this.itemRenderer.renderItemInFirstPerson(par1);
              this.disableLightmap(par1);
           }

           Draw.popPose();
           if (this.mc.gameSettings.thirdPersonView == 0 && !this.mc.renderViewEntity.inBed()) {
              this.itemRenderer.renderOverlays(par1);
              this.hurtCameraEffect(par1);
           }

           if (this.mc.gameSettings.viewBobbing) {
              this.setupViewBobbing(par1);
           }
        }
     }

    /** @author FML-Vulkan NR-12 @reason native setupOverlayRendering (Draw/Frame API) */
    @Overwrite
     public void setupOverlayRendering() {
        ScaledResolution var1 = new ScaledResolution(this.mc.gameSettings, this.mc.displayWidth, this.mc.displayHeight);
        Frame.clearDepth();
        Draw.projection().loadIdentity();
        Draw.projection().ortho(0.0, var1.getScaledWidth_double(), var1.getScaledHeight_double(), 0.0, 1000.0, 3000.0);
        Draw.pose().loadIdentity();
        Draw.pose().translate(0.0F, 0.0F, -2000.0F);
     }

    /** @author FML-Vulkan NR-12 @reason native disableLightmap (Draw/Frame API) */
    @Overwrite
    public void disableLightmap(double par1) {
        Draw.activeUnit(1);
        Draw.texturing(false);
        Draw.activeUnit(0);
    }

    /** @author FML-Vulkan NR-12 @reason native enableLightmap (Draw/Frame API) */
    @Overwrite
    public void enableLightmap(double par1) {
        Draw.activeUnit(1);
        // MITE's scale(1/256)+translate(8) texture matrix targets unit 1; the shader folds (c+8)/256 itself
        // (ARCHITECTURE-NATIVE 1.10), so Draw.uvMatrix() (unit 0) must stay untouched.
        this.mc.getTextureManager().bindTexture(this.locationLightMap);
        // glTexParameteri dropped: filter/wrap come from the lightmap dynamic texture
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
        Draw.texturing(true);
        Draw.activeUnit(0);
    }
}
