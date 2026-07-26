package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge compatibility for {@link RendererLivingEntity}.
 * <p>
 * Modifications:
 * <ul>
 *   <li>Add {@code NAME_TAG_RANGE} and {@code NAME_TAG_RANGE_SNEAK}
 *       static fields.</li>
 *   <li>Fire {@link RenderLivingEvent.Pre} and {@code .Post}.</li>
 *   <li>Fire {@link RenderLivingEvent.Specials.Pre} and {@code .Post}.</li>
 * </ul>
 */
@Mixin(RendererLivingEntity.class)
public class RendererLivingEntityMixin {

    @Unique
    public static float NAME_TAG_RANGE = 64.0f;

    @Unique
    public static float NAME_TAG_RANGE_SNEAK = 32.0f;

    /**
     * Fires {@link RenderLivingEvent.Pre} at the top of
     * {@code doRenderLiving} and {@code RenderLivingEvent.Post}
     * at the bottom.
     * <p>
     * The HEAD inject handles Pre (cancellable). The RETURN inject
     * handles Post.
     */
    @Inject(method = "doRenderLiving", at = @At("HEAD"), cancellable = true)
    private void fmlForgeOnDoRenderLivingPre(EntityLivingBase par1EntityLivingBase,
                                              double par2, double par4, double par6,
                                              float par8, float par9, CallbackInfo ci) {
        if (MinecraftForge.EVENT_BUS.post(
                new RenderLivingEvent.Pre(par1EntityLivingBase,
                        (RendererLivingEntity)(Object)this))) {
            ci.cancel();
        }
    }

    @Inject(method = "doRenderLiving", at = @At("RETURN"))
    private void fmlForgeOnDoRenderLivingPost(EntityLivingBase par1EntityLivingBase,
                                               double par2, double par4, double par6,
                                               float par8, float par9, CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(
                new RenderLivingEvent.Post(par1EntityLivingBase,
                        (RendererLivingEntity)(Object)this));
    }

    /**
     * Placeholder: The patch fires
     * {@link RenderLivingEvent.Specials.Pre} at the top of
     * {@code passSpecialRender} and {@code Specials.Post} at the
     * bottom. Also replaces the hardcoded name-tag range values
     * with the static fields.
     * <p>
     * The inject at HEAD (cancellable) and RETURN should handle
     * the events, but the range values in the middle require
     * direct patching.
     */
    @Inject(method = "passSpecialRender", at = @At("HEAD"), cancellable = true)
    private void fmlForgeOnPassSpecialRenderPre(EntityLivingBase par1EntityLivingBase,
                                                 double par2, double par4, double par6,
                                                 CallbackInfo ci) {
        if (MinecraftForge.EVENT_BUS.post(
                new RenderLivingEvent.Specials.Pre(par1EntityLivingBase,
                        (RendererLivingEntity)(Object)this))) {
            ci.cancel();
        }
    }

    @Inject(method = "passSpecialRender", at = @At("RETURN"))
    private void fmlForgeOnPassSpecialRenderPost(EntityLivingBase par1EntityLivingBase,
                                                  double par2, double par4, double par6,
                                                  CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(
                new RenderLivingEvent.Specials.Post(par1EntityLivingBase,
                        (RendererLivingEntity)(Object)this));
    }
}
