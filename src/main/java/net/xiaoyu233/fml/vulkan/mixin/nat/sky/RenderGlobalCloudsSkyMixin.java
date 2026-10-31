package net.xiaoyu233.fml.vulkan.mixin.nat.sky;

import net.minecraft.Minecraft;
import net.minecraft.RenderGlobal;
import net.minecraft.WorldClient;
import net.xiaoyu233.fml.vulkan.render.sky.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Native area "sky": cloud methods of RenderGlobal (NR-11), delegating to {@link CloudRenderer}. Never touches {@code <init>}.
 * The class exposes the private fields the renderer needs through {@link CloudRenderer.Access}.
 */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalCloudsSkyMixin implements CloudRenderer.Access {
    @Shadow
    private Minecraft mc;
    @Shadow
    private WorldClient theWorld;
    @Shadow
    private int cloudTickCounter;

    @Unique
    @Override
    public WorldClient nr11$world() {
        return this.theWorld;
    }

    @Unique
    @Override
    public int nr11$cloudTick() {
        return this.cloudTickCounter;
    }

    /**
     * @author fml-native
     * @reason native cloud renderer
     */
    @Overwrite
    public void compileCloudsFancy(float par1, double var8, double var2, double var10) {
        CloudRenderer.compileCloudsFancy((RenderGlobal) (Object) this, par1, var8, var2, var10);
    }

    /**
     * @author fml-native
     * @reason native cloud renderer
     */
    @Overwrite
    public void renderCloudsFancy_MITE(float par1) {
        CloudRenderer.renderCloudsFancyMite((RenderGlobal) (Object) this, par1);
    }

    /**
     * The call expression {@code isSurfaceWorld()} stays here: Twilight Forest's ModifyExpressionValue targets it.
     *
     * @author fml-native
     * @reason native cloud renderer
     */
    @Overwrite
    public void renderClouds(float par1) {
        if (this.mc.theWorld.provider.isSurfaceWorld()) {
            CloudRenderer.renderClouds((RenderGlobal) (Object) this, par1);
        }
    }

    /**
     * @author fml-native
     * @reason native cloud renderer
     */
    @Overwrite
    public void renderCloudsFancy(float par1) {
        CloudRenderer.renderCloudsFancy((RenderGlobal) (Object) this, par1);
    }

    /**
     * @author fml-native
     * @reason native cloud renderer
     */
    @Overwrite
    public void renderCloudSide(float par1, int side, double x, double z, boolean tops, boolean bottoms) {
        CloudRenderer.renderCloudSide((RenderGlobal) (Object) this, par1, side, x, z, tops, bottoms);
    }
}
