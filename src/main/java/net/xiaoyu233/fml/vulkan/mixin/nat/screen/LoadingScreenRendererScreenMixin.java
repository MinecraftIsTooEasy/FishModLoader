package net.xiaoyu233.fml.vulkan.mixin.nat.screen;

import net.minecraft.LoadingScreenRenderer;
import net.minecraft.Minecraft;
import net.minecraft.MinecraftError;
import net.minecraft.ScaledResolution;
import net.xiaoyu233.fml.vulkan.render.screen.LoadingFrames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native loading screens (3.11): MITE throttle and running checks kept, drawing delegated to {@link LoadingFrames}. */
@Mixin(LoadingScreenRenderer.class)
public abstract class LoadingScreenRendererScreenMixin {
    @Shadow private String field_73727_a;
    @Shadow private Minecraft mc;
    @Shadow private String currentlyDisplayedText;
    @Shadow private long field_73723_d;
    @Shadow private boolean field_73724_e;

    /**
     * @author NR-18
     * @reason native ortho setup
     */
    @Overwrite
    public void func_73722_d(String text) {
        this.currentlyDisplayedText = text;
        if (!LoadingFrames.running(this.mc)) {
            if (!this.field_73724_e) {
                throw new MinecraftError();
            }
        } else {
            ScaledResolution sr = new ScaledResolution(this.mc.gameSettings, this.mc.displayWidth, this.mc.displayHeight);
            LoadingFrames.beginFrame(sr.getScaledWidth_double(), sr.getScaledHeight_double());
        }
    }

    /**
     * @author NR-18
     * @reason native loading frame
     */
    @Overwrite
    public void setLoadingProgress(int percent) {
        if (!LoadingFrames.running(this.mc)) {
            if (!this.field_73724_e) {
                throw new MinecraftError();
            }
        } else {
            long now = Minecraft.getSystemTime();
            if (now - this.field_73723_d >= 100L) {
                this.field_73723_d = now;
                LoadingFrames.drawProgress((LoadingScreenRenderer) (Object) this, this.mc, this.currentlyDisplayedText, this.field_73727_a, percent);
            }
        }
    }
}
