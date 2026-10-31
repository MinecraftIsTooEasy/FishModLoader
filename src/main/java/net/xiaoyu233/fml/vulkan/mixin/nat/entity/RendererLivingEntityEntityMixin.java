package net.xiaoyu233.fml.vulkan.mixin.nat.entity;

import java.util.Random;
import net.minecraft.Entity;
import net.minecraft.EntityArrow;
import net.minecraft.EntityLivingBase;
import net.minecraft.EntityPlayer;
import net.minecraft.EnumChatFormatting;
import net.minecraft.FontRenderer;
import net.minecraft.Item;
import net.minecraft.MathHelper;
import net.minecraft.Minecraft;
import net.minecraft.ModelBase;
import net.minecraft.ModelBox;
import net.minecraft.ModelRenderer;
import net.minecraft.OpenGlHelper;
import net.minecraft.Potion;
import net.minecraft.Render;
import net.minecraft.RenderHelper;
import net.minecraft.RendererLivingEntity;
import net.minecraft.ResourceLocation;
import net.minecraft.World;
import net.xiaoyu233.fml.vulkan.render.entity.EntityEffects;
import net.xiaoyu233.fml.vulkan.render.state.AttribMask;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.DepthFunc;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Native area "entity": {@code RendererLivingEntity} (ARCHITECTURE-NATIVE 3.7). MITE's structure and order are kept; hurt/death flash and
 * {@code getColorMultiplier} are a pre-composited per-vertex overlay instead of the two extra EQUAL passes.
 */
@Mixin(RendererLivingEntity.class)
public abstract class RendererLivingEntityEntityMixin extends Render {
    @Shadow @Final
    private static ResourceLocation RES_ITEM_GLINT;
    @Shadow
    protected ModelBase mainModel;
    @Shadow
    protected ModelBase renderPassModel;

    /** Scratch rgba for {@code getColorMultiplier} (render thread only; avoids a float[] per entity per frame). */
    @Unique
    private float[] nativeOverlayMul;
    /** Reused stand-in arrow for the stuck-arrow pass; rebuilt when the entity's world changes. */
    @Unique
    private EntityArrow nativeStuckArrow;
    @Unique
    private World nativeStuckArrowWorld;
    /** Reused RNG for the stuck-arrow pass, reseeded per entity exactly as {@code new Random(entityId)}. */
    @Unique
    private Random nativeStuckRandom;

    @Shadow
    private float interpolateRotation(float a, float b, float t) {
        throw new AbstractMethodError();
    }

    @Shadow
    protected abstract float renderSwingProgress(EntityLivingBase e, float pt);

    @Shadow
    protected abstract float handleRotationFloat(EntityLivingBase e, float pt);

    @Shadow
    protected abstract void preRenderCallback(EntityLivingBase e, float pt);

    @Shadow
    protected abstract int shouldRenderPass(EntityLivingBase e, int pass, float pt);

    @Shadow
    protected abstract int inheritRenderPass(EntityLivingBase e, int pass, float pt);

    @Shadow
    protected abstract void func_82408_c(EntityLivingBase e, int pass, float pt);

    @Shadow
    protected abstract void renderEquippedItems(EntityLivingBase e, float pt);

    @Shadow
    protected abstract int getColorMultiplier(EntityLivingBase e, float brightness, float pt);

    @Shadow
    protected abstract float getDeathMaxRotation(EntityLivingBase e);

    @Shadow
    protected abstract boolean func_110813_b(EntityLivingBase e);

    @Shadow
    protected abstract void func_96449_a(EntityLivingBase e, double x, double y, double z, String name, float scale, double distSq);

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    public void doRenderLiving(EntityLivingBase entity, double x, double y, double z, float yaw, float partialTicks) {
        Draw.pushPose();
        if (entity.drawBackFaces()) {
            Draw.cull(false);
        } else {
            Draw.cull(true);
        }

        this.mainModel.onGround = this.renderSwingProgress(entity, partialTicks);
        if (this.renderPassModel != null) {
            this.renderPassModel.onGround = this.mainModel.onGround;
        }

        this.mainModel.isRiding = entity.isRiding();
        if (this.renderPassModel != null) {
            this.renderPassModel.isRiding = this.mainModel.isRiding;
        }

        this.mainModel.isChild = entity.isChild();
        if (this.renderPassModel != null) {
            this.renderPassModel.isChild = this.mainModel.isChild;
        }

        int poseDepthAtEntry = Draw.pose().depth();
        boolean completed = false;
        boolean overlayOn = false;
        boolean attribPushed = false;
        try {
            float bodyYaw = this.interpolateRotation(entity.prevRenderYawOffset, entity.renderYawOffset, partialTicks);
            float headYaw = this.interpolateRotation(entity.prevRotationYawHead, entity.rotationYawHead, partialTicks);
            if (entity.isPotionActive(Potion.confusion)) {
                headYaw = (float) (headYaw - Math.cos(entity.ticksExisted * 0.15F) * 3.0);
            }

            if (entity.isRiding() && entity.ridingEntity instanceof EntityLivingBase) {
                EntityLivingBase mount = (EntityLivingBase) entity.ridingEntity;
                bodyYaw = this.interpolateRotation(mount.prevRenderYawOffset, mount.renderYawOffset, partialTicks);
                float diff = MathHelper.wrapAngleTo180_float(headYaw - bodyYaw);
                if (diff < -85.0F) {
                    diff = -85.0F;
                }

                if (diff >= 85.0F) {
                    diff = 85.0F;
                }

                bodyYaw = headYaw - diff;
                if (diff * diff > 2500.0F) {
                    bodyYaw += diff * 0.2F;
                }
            }

            float pitch = entity.prevRotationPitch + (entity.rotationPitch - entity.prevRotationPitch) * partialTicks;
            if (entity.isPotionActive(Potion.confusion)) {
                pitch = (float) (pitch + Math.sin(entity.ticksExisted * 0.15F) * 6.0);
            }

            this.renderLivingAt(entity, x, y, z);
            float age = this.handleRotationFloat(entity, partialTicks);
            this.rotateCorpse(entity, age, bodyYaw, partialTicks);
            float scale = 0.0625F;
            Draw.rescaleNormal(true);
            Draw.scale(-1.0F, -1.0F, 1.0F);
            this.preRenderCallback(entity, partialTicks);
            Draw.translate(0.0F, -24.0F * scale - 0.0078125F, 0.0F);
            float limbAmount = entity.prevLimbSwingAmount + (entity.limbSwingAmount - entity.prevLimbSwingAmount) * partialTicks;
            float limbSwing = entity.limbSwing - entity.limbSwingAmount * (1.0F - partialTicks);
            if (entity.isChild()) {
                limbSwing *= 3.0F;
            }

            if (limbAmount > 1.0F) {
                limbAmount = 1.0F;
            }

            Draw.alphaTest(true);
            this.mainModel.setLivingAnimations(entity, limbSwing, limbAmount, partialTicks);

            // hurt/death flash and getColorMultiplier: pre-composited overlay instead of MITE's two extra EQUAL passes
            float flashBrightness = entity.getBrightness(partialTicks);
            int colorMul = this.getColorMultiplier(entity, flashBrightness, partialTicks);
            boolean hurtFlash = entity.hurtTime > 0 || entity.deathTime > 0;
            boolean hasMul = (colorMul >> 24 & 0xFF) > 0;
            float[] mulRgba = null;
            if (hasMul) {
                if (this.nativeOverlayMul == null) {
                    this.nativeOverlayMul = new float[4];
                }
                mulRgba = this.nativeOverlayMul;
                mulRgba[0] = (colorMul >> 16 & 0xFF) / 255.0F;
                mulRgba[1] = (colorMul >> 8 & 0xFF) / 255.0F;
                mulRgba[2] = (colorMul & 0xFF) / 255.0F;
                mulRgba[3] = (colorMul >> 24 & 0xFF) / 255.0F;
            }
            overlayOn = hurtFlash || hasMul;
            if (overlayOn) {
                EntityEffects.overlayFromHurt(hurtFlash ? flashBrightness : -1.0F, mulRgba);
            }

            this.renderModel(entity, limbSwing, limbAmount, age, headYaw - bodyYaw, pitch, scale);

            for (int pass = 0; pass < 4; pass++) {
                int passFlags = this.shouldRenderPass(entity, pass, partialTicks);
                // MITE calls inheritRenderPass for all four passes whenever the flash is on (its side effects are kept), and tints a
                // pass model only where it is >= 0. The overlay is baked per vertex, so the decision is taken here, before the pass is
                // emitted. Passes with a non-standard blend (additive charge or glow layers) take no overlay, because the mix would
                // add the red instead of blending it over the pixels as MITE does.
                boolean passFlash = false;
                if (overlayOn) {
                    boolean inherit = this.inheritRenderPass(entity, pass, partialTicks) >= 0;
                    boolean normalBlend = !Draw.blendEnabled()
                        || (Draw.blendSrc() == BlendFactor.SRC_ALPHA && Draw.blendDst() == BlendFactor.ONE_MINUS_SRC_ALPHA);
                    passFlash = inherit && normalBlend;
                }

                if (passFlags > 0) {
                    this.renderPassModel.setLivingAnimations(entity, limbSwing, limbAmount, partialTicks);
                    if (passFlash) {
                        EntityEffects.overlayFromHurt(hurtFlash ? flashBrightness : -1.0F, mulRgba);
                    } else {
                        Draw.overlayNone();
                    }

                    this.renderPassModel.render(entity, limbSwing, limbAmount, age, headYaw - bodyYaw, pitch, scale);
                    // the second armour draw, glint and energy layers below never carry the overlay
                    Draw.overlayNone();
                    if ((passFlags & 240) == 16) {
                        this.func_82408_c(entity, pass, partialTicks);
                        this.renderPassModel.render(entity, limbSwing, limbAmount, age, headYaw - bodyYaw, pitch, scale);
                    }

                    if ((passFlags & 15) == 15) {
                        float time = entity.ticksExisted + partialTicks;
                        this.bindTexture(RES_ITEM_GLINT);
                        Draw.blend(true);
                        float grey = 0.5F;
                        Draw.color(grey, grey, grey, 1.0F);
                        Draw.depthFunc(DepthFunc.EQUAL);
                        Draw.depthMask(false);

                        for (int glintPass = 0; glintPass < 2; glintPass++) {
                            EntityEffects.beginGlint(glintPass, time);
                            this.renderPassModel.render(entity, limbSwing, limbAmount, age, headYaw - bodyYaw, pitch, scale);
                        }

                        EntityEffects.endGlint();
                    }

                    Draw.blend(false);
                    Draw.alphaTest(true);
                }
            }

            if (overlayOn) {
                Draw.overlayNone();
            }

            Draw.depthMask(true);
            this.renderEquippedItems(entity, partialTicks);
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            Draw.texturing(false);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            if (entity.tagged) {
                // x-ray pass: separate emission with the depth test off
                Draw.texturing(false);
                Draw.alphaTest(false);
                Draw.blend(true);
                Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
                Draw.depthFunc(DepthFunc.EQUAL);
                Draw.pushAttrib(AttribMask.decode(2929));
                attribPushed = true;
                Draw.depthTest(false);
                Draw.color(flashBrightness, 0.0F, 0.0F, 0.4F);
                this.mainModel.render(entity, limbSwing, limbAmount, age, headYaw - bodyYaw, pitch, scale);
                Draw.popAttrib();
                attribPushed = false;
                Draw.depthFunc(DepthFunc.LEQUAL);
                Draw.blend(false);
                Draw.alphaTest(true);
                Draw.texturing(true);
            }

            Draw.rescaleNormal(false);
            completed = true;
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            // unwind whatever a model or pass left open; on a normal exit these are already at their resting values.
            // Overlay and attribute stack are not reset elsewhere on a throw, and the overlay would tint every later draw.
            if (overlayOn) {
                Draw.overlayNone();
            }

            if (attribPushed) {
                Draw.popAttrib();
            }

            while (Draw.pose().depth() > poseDepthAtEntry) {
                Draw.popPose();
            }

            Draw.uvMatrix().loadIdentity();
            Draw.depthFunc(DepthFunc.LEQUAL);
            Draw.depthMask(true);
            Draw.blend(false);
            Draw.rescaleNormal(false);
            if (!completed) {
                // a glint or model pass threw part-way: restore the lighting, colour and alpha test it may have left set
                Draw.lighting(true);
                Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
                Draw.alphaTest(true);
            }
        }

        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        Draw.texturing(true);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        Draw.cull(true);
        Draw.popPose();
        this.passSpecialRender(entity, x, y, z);
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    protected void renderModelGlowing(EntityLivingBase entity, float a, float b, float c, float d, float e, float f) {
        ResourceLocation glowing = this.getGlowingTextureCounterpart(this.getEntityTexture(entity));
        if (glowing != null) {
            this.use_glowing_texture = true;
            this.bindTexture(glowing);
            Draw.depthMask(!entity.isInvisible());
            // MITE sequence: setLightmapTextureCoords(240, 240) shifts current into previous, and restorePrevious then
            // returns the entity's own light (set by RenderManager.renderEntity / TileEntityRenderer.renderTileEntity)
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
            Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
            this.mainModel.render(entity, a, b, c, d, e, f);
            OpenGlHelper.restorePreviousLightmapTextureCoords();
            this.use_glowing_texture = false;
        }
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    protected void renderModel(EntityLivingBase entity, float a, float b, float c, float d, float e, float f) {
        this.bindEntityTexture(entity);
        if (!entity.isInvisible()) {
            float alpha = this.renderManager.getEntityRenderObject(entity).getModelOpacity(entity);
            if (alpha < 0.99F) {
                Draw.pushPose();
                Draw.color(1.0F, 1.0F, 1.0F, alpha);
                Draw.depthMask(false);
                Draw.blend(true);
                Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
                Draw.alphaFunc(0.003921569F);
            }

            this.mainModel.render(entity, a, b, c, d, e, f);
            this.renderModelGlowing(entity, a, b, c, d, e, f);
            if (alpha < 0.99F) {
                Draw.blend(false);
                Draw.alphaFunc(0.1F);
                Draw.popPose();
                Draw.depthMask(true);
            }
        } else if (!entity.isInvisibleToPlayer(Minecraft.getMinecraft().thePlayer)) {
            Draw.pushPose();
            Draw.color(1.0F, 1.0F, 1.0F, 0.15F);
            Draw.depthMask(false);
            Draw.blend(true);
            Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
            Draw.alphaFunc(0.003921569F);
            this.mainModel.render(entity, a, b, c, d, e, f);
            this.renderModelGlowing(entity, a, b, c, d, e, f);
            Draw.blend(false);
            Draw.alphaFunc(0.1F);
            Draw.popPose();
            Draw.depthMask(true);
        } else {
            this.mainModel.setRotationAngles(a, b, c, d, e, f, entity);
        }
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    protected void renderLivingAt(EntityLivingBase entity, double x, double y, double z) {
        Draw.translate((float) x, (float) y, (float) z);
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    protected void rotateCorpse(EntityLivingBase entity, float age, float yaw, float partialTicks) {
        Draw.rotate(180.0F - yaw, 0.0F, 1.0F, 0.0F);
        if (entity.deathTime > 0) {
            float fall = (entity.deathTime + partialTicks - 1.0F) / 20.0F * 1.6F;
            fall = MathHelper.sqrt_float(fall);
            if (fall > 1.0F) {
                fall = 1.0F;
            }

            Draw.rotate(fall * this.getDeathMaxRotation(entity), 0.0F, 0.0F, 1.0F);
        } else {
            String name = EnumChatFormatting.func_110646_a(entity.getEntityName());
            if ((name.equals("Dinnerbone") || name.equals("Grumm"))
                && (!(entity instanceof EntityPlayer) || !((EntityPlayer) entity).getHideCape())) {
                Draw.translate(0.0F, entity.height + 0.1F, 0.0F);
                Draw.rotate(180.0F, 0.0F, 0.0F, 1.0F);
            }
        }
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    protected void renderArrowsStuckInEntity(EntityLivingBase entity, float partialTicks) {
        int count = entity.getArrowCountInEntity();
        if (count > 0) {
            // one arrow entity per renderer (rebuilt only when the world changes) and one Random reseeded per entity
            if (this.nativeStuckArrow == null || this.nativeStuckArrowWorld != entity.worldObj) {
                this.nativeStuckArrow = new EntityArrow(entity.worldObj, entity.posX, entity.posY, entity.posZ, Item.arrowIron, false);
                this.nativeStuckArrowWorld = entity.worldObj;
            } else {
                this.nativeStuckArrow.setPosition(entity.posX, entity.posY, entity.posZ);
            }
            EntityArrow arrow = this.nativeStuckArrow;
            if (this.nativeStuckRandom == null) {
                this.nativeStuckRandom = new Random(entity.entityId);
            } else {
                this.nativeStuckRandom.setSeed(entity.entityId);
            }
            Random random = this.nativeStuckRandom;
            RenderHelper.disableStandardItemLighting();

            for (int i = 0; i < count; i++) {
                Draw.pushPose();
                ModelRenderer part = this.mainModel.getRandomModelBox(random);
                ModelBox box = (ModelBox) part.cubeList.get(random.nextInt(part.cubeList.size()));
                part.postRender(0.0625F);
                float rx = random.nextFloat();
                float ry = random.nextFloat();
                float rz = random.nextFloat();
                float px = (box.posX1 + (box.posX2 - box.posX1) * rx) / 16.0F;
                float py = (box.posY1 + (box.posY2 - box.posY1) * ry) / 16.0F;
                float pz = (box.posZ1 + (box.posZ2 - box.posZ1) * rz) / 16.0F;
                Draw.translate(px, py, pz);
                rx = rx * 2.0F - 1.0F;
                ry = ry * 2.0F - 1.0F;
                rz = rz * 2.0F - 1.0F;
                rx *= -1.0F;
                ry *= -1.0F;
                rz *= -1.0F;
                float horizontal = MathHelper.sqrt_float(rx * rx + rz * rz);
                arrow.prevRotationYaw = arrow.rotationYaw = (float) (Math.atan2(rx, rz) * 180.0 / Math.PI);
                arrow.prevRotationPitch = arrow.rotationPitch = (float) (Math.atan2(ry, horizontal) * 180.0 / Math.PI);
                double ox = 0.0;
                double oy = 0.0;
                double oz = 0.0;
                float oyaw = 0.0F;
                this.renderManager.renderEntityWithPosYaw(arrow, ox, oy, oz, oyaw, partialTicks);
                Draw.popPose();
            }

            RenderHelper.enableStandardItemLighting();
        }
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    protected void passSpecialRender(EntityLivingBase entity, double x, double y, double z) {
        if (this.func_110813_b(entity)) {
            float labelScale = 1.6F;
            float scale = 0.016666668F * labelScale;
            double distSq = entity.getDistanceSqToEntity(this.renderManager.livingPlayer);
            float maxDist = entity.isSneaking() ? 32.0F : 64.0F;
            if (distSq < maxDist * maxDist) {
                String name = entity.getTranslatedEntityName();
                if (entity.isSneaking()) {
                    EntityEffects.drawLabel(name, (float) x, (float) y + entity.height + 0.5F, (float) z,
                        this.renderManager.playerViewY, this.renderManager.playerViewX, scale, (int) (maxDist * maxDist), true);
                } else {
                    this.func_96449_a(entity, x, y, z, name, scale, distSq);
                }
            }
        }
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    protected void renderLivingLabel(EntityLivingBase entity, String text, double x, double y, double z, int maxDistance) {
        if (entity.isEntityPlayer()) {
            EntityPlayer player = entity.getAsPlayer();
            if (player.isGhost() || player.isZevimrgvInTournament()) {
                return;
            }
        }

        double distSq = entity.getDistanceSqToEntity(this.renderManager.livingPlayer);
        if (distSq <= maxDistance * maxDistance) {
            float labelScale = 1.6F;
            float scale = 0.016666668F * labelScale;
            float pitch = this.renderManager.options.thirdPersonView == 2 ? -this.renderManager.playerViewX : this.renderManager.playerViewX;
            EntityEffects.drawLabel(text, (float) x, (float) y + entity.height + 0.5F, (float) z,
                this.renderManager.playerViewY, pitch, scale, maxDistance * maxDistance, false);
        }
    }
}
