package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge compatibility for {@link RenderPlayer}.
 * <p>
 * Modifications:
 * <ul>
 *   <li>Fire {@link RenderPlayerEvent.SetArmorModel}.</li>
 *   <li>Use {@code RenderBiped.getArmorResource}.</li>
 *   <li>Use {@code ForgeHooksClient.getArmorModel}.</li>
 *   <li>Color handling extended beyond CLOTH armor.</li>
 *   <li>Fire {@link RenderPlayerEvent.Pre/Post}.</li>
 *   <li>Fire {@link RenderPlayerEvent.Specials.Pre/Post}.</li>
 *   <li>Event-controlled rendering of helmet, cape, item.</li>
 *   <li>Custom item renderer + dynamic render passes.</li>
 * </ul>
 */
@Mixin(RenderPlayer.class)
public class RenderPlayerMixin {

    @Shadow
    private ModelBiped modelBipedMain;

    @Shadow
    private ModelBiped modelArmorChestplate;

    @Shadow
    private ModelBiped modelArmor;

    /**
     * Placeholder: The patch fires
     * {@link RenderPlayerEvent.SetArmorModel} at the top of the
     * armor-rendering method, and {@code RenderPlayerEvent.Pre/Post}
     * wrapping the main render method.
     * <p>
     * It also fires {@code RenderPlayerEvent.Specials.Pre/Post}
     * wrapping the specials rendering, and adds event-controlled
     * flags (renderHelmet, renderCape, renderItem).
     * <p>
     * The {@code Pre} events can be HEAD-injected (cancellable).
     * The {@code Post} events can be RETURN-injected.
     * The body-level changes require direct patching.
     */
    @Unique
    private void fmlForgeRenderPlayerEvents() {
        throw new UnsupportedOperationException(
                "Extensive body changes required. See patches for RenderPlayer events.");
    }

    /**
     * Fires {@link RenderPlayerEvent.Pre} at the top of
     * {@code func_130009_a} (the main render method).
     */
    @Inject(method = "func_130009_a", at = @At("HEAD"), cancellable = true)
    private void fmlForgeOnRenderPlayerPre(
            AbstractClientPlayer par1AbstractClientPlayer,
            double par2, double par4, double par6, float par8, float par9,
            CallbackInfo ci) {
        if (MinecraftForge.EVENT_BUS.post(
                new RenderPlayerEvent.Pre(par1AbstractClientPlayer,
                        (RenderPlayer)(Object)this, par9))) {
            ci.cancel();
        }
    }

    /**
     * Fires {@link RenderPlayerEvent.Post} at the bottom of
     * {@code func_130009_a}.
     */
    @Inject(method = "func_130009_a", at = @At("RETURN"))
    private void fmlForgeOnRenderPlayerPost(
            AbstractClientPlayer par1AbstractClientPlayer,
            double par2, double par4, double par6, float par8, float par9,
            CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(
                new RenderPlayerEvent.Post(par1AbstractClientPlayer,
                        (RenderPlayer)(Object)this, par9));
    }

    /**
     * Fires {@link RenderPlayerEvent.Specials.Pre} at the top of
     * {@code renderSpecials}.
     */
    @Inject(method = "renderSpecials", at = @At("HEAD"), cancellable = true)
    private void fmlForgeOnRenderPlayerSpecialsPre(
            AbstractClientPlayer par1AbstractClientPlayer,
            float par2, CallbackInfo ci) {
        RenderPlayerEvent.Specials.Pre event =
                new RenderPlayerEvent.Specials.Pre(par1AbstractClientPlayer,
                        (RenderPlayer)(Object)this, par2);
        if (MinecraftForge.EVENT_BUS.post(event)) {
            ci.cancel();
        }
    }

    /**
     * Fires {@link RenderPlayerEvent.Specials.Post} at the bottom of
     * {@code renderSpecials}.
     */
    @Inject(method = "renderSpecials", at = @At("RETURN"))
    private void fmlForgeOnRenderPlayerSpecialsPost(
            AbstractClientPlayer par1AbstractClientPlayer,
            float par2, CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(
                new RenderPlayerEvent.Specials.Post(par1AbstractClientPlayer,
                        (RenderPlayer)(Object)this, par2));
    }
}
