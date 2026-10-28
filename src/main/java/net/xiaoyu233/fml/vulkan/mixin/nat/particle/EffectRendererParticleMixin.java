package net.xiaoyu233.fml.vulkan.mixin.nat.particle;

import java.util.List;
import net.minecraft.EffectRenderer;
import net.minecraft.Entity;
import net.minecraft.EntityFX;
import net.minecraft.TextureManager;
import net.xiaoyu233.fml.vulkan.render.particle.ParticleFastPath;
import net.xiaoyu233.fml.vulkan.render.particle.ParticleRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "particle": EffectRenderer render + fast path entry points (3.10). */
@Mixin(EffectRenderer.class)
public abstract class EffectRendererParticleMixin {
    @Shadow
    private List[] fxLayers;
    @Shadow
    private TextureManager renderer;

    /**
     * @author fml-native
     * @reason native renderer: one Batches entry per particle layer, MITE state kept
     */
    @Overwrite
    public void renderParticles(Entity camera, float partialTicks) {
        ParticleRenderer.renderParticles(this.fxLayers, this.renderer, camera, partialTicks);
    }

    /**
     * @author fml-native
     * @reason native renderer: lit particles keep per-particle state, merged by RenderType
     */
    @Overwrite
    public void renderLitParticles(Entity camera, float partialTicks) {
        ParticleRenderer.renderLitParticles(this.fxLayers, camera, partialTicks);
    }

    /**
     * @author fml-native
     * @reason native renderer: fast path compaction, cap of 4000 per layer kept
     */
    @Overwrite
    @SuppressWarnings("unchecked")
    public void addEffect(EntityFX fx) {
        if (ParticleFastPath.enabled()) {
            ParticleFastPath.addEffect(this.fxLayers, fx);
            return;
        }
        int layer = fx.getFXLayer();
        if (this.fxLayers[layer].size() >= 4000) {
            this.fxLayers[layer].remove(0);
        }
        this.fxLayers[layer].add(fx);
    }

    /**
     * @author fml-native
     * @reason native renderer: fast path update with dead-particle compaction
     */
    @Overwrite
    public void updateEffects() {
        if (ParticleFastPath.enabled()) {
            ParticleFastPath.updateEffects(this.fxLayers);
            return;
        }
        for (int l = 0; l < 4; l++) {
            for (int i = 0; i < this.fxLayers[l].size(); i++) {
                EntityFX fx = (EntityFX) this.fxLayers[l].get(i);
                fx.onUpdate();
                if (fx.isDead) {
                    this.fxLayers[l].remove(i--);
                }
            }
        }
    }
}
