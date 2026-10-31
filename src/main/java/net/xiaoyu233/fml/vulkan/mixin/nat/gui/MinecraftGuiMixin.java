package net.xiaoyu233.fml.vulkan.mixin.nat.gui;

import net.minecraft.Minecraft;
import net.xiaoyu233.fml.vulkan.render.gui.DebugChart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "gui": Minecraft.displayDebugInfo (profiler pie chart) delegates to {@link DebugChart}. */
@Mixin(Minecraft.class)
public abstract class MinecraftGuiMixin implements DebugChart.ProfilerNameAccess {
    @Shadow
    private String debugProfilerName;

    @Override
    public String fml$debugProfilerName() {
        return this.debugProfilerName;
    }

    /**
     * @author NR-17
     * @reason native debug chart, no GL
     */
    @Overwrite
    private void displayDebugInfo(long par1) {
        DebugChart.render((Minecraft) (Object) this, par1);
    }
}
