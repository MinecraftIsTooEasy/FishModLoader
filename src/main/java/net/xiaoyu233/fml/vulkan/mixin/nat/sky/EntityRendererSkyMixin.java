package net.xiaoyu233.fml.vulkan.mixin.nat.sky;

import java.util.Random;
import net.minecraft.EntityRenderer;
import net.minecraft.Minecraft;
import net.minecraft.RenderGlobal;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.sky.WeatherRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "sky": weather and cloud check delegate to the native renderers (Twilight Forest's HEAD injection into renderRainSnow still applies). */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererSkyMixin implements WeatherRenderer.Access {
    @Shadow
    private Minecraft mc;

    @Shadow
    private void setupFog(int mode, float partialTicks) {
    }
    @Shadow
    float[] rainXCoords;
    @Shadow
    float[] rainYCoords;
    @Shadow
    private Random random;
    @Shadow
    private int rendererUpdateCount;
    @Shadow
    private int last_precipitation_type;

    /**
     * @author fml-native
     * @reason native weather renderer
     */
    @Overwrite
    protected void renderRainSnow(float partialTicks) {
        WeatherRenderer.renderRainSnow((EntityRenderer) (Object) this, this.mc, partialTicks);
    }

    /**
     * @author fml-native
     * @reason native cloud check
     */
    @Overwrite
    private void renderCloudsCheck(RenderGlobal rg, float partialTicks) {
        if (this.mc.gameSettings.shouldRenderClouds()) {
            this.mc.mcProfiler.endStartSection("clouds");
            Draw.pushPose();
            this.setupFog(0, partialTicks);
            Draw.fog(true);
            rg.renderClouds(partialTicks);
            Draw.fog(false);
            this.setupFog(1, partialTicks);
            Draw.popPose();
        }
    }

    @Override
    public float[] fml$rainX() {
        return this.rainXCoords;
    }

    @Override
    public float[] fml$rainY() {
        return this.rainYCoords;
    }

    @Override
    public void fml$setRain(float[] x, float[] y) {
        this.rainXCoords = x;
        this.rainYCoords = y;
    }

    @Override
    public Random fml$random() {
        return this.random;
    }

    @Override
    public int fml$updateCount() {
        return this.rendererUpdateCount;
    }

    @Override
    public int fml$lastPrecipitationType() {
        return this.last_precipitation_type;
    }

    @Override
    public void fml$setLastPrecipitationType(int t) {
        this.last_precipitation_type = t;
    }
}
