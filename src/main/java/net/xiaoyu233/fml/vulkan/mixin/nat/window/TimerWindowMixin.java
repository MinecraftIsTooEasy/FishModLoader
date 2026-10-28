package net.xiaoyu233.fml.vulkan.mixin.nat.window;

import net.minecraft.Timer;
import net.xiaoyu233.fml.vulkan.window.TimerCore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Native area "window", PAC-06: {@code Timer.updateTimer} on {@code System.nanoTime()} (see {@link TimerCore}). The tick arithmetic
 * (float partial ticks, cap of 10 ticks per frame) is MITE's, so the tick count over any period is identical.
 */
@Mixin(Timer.class)
public abstract class TimerWindowMixin {
    @Shadow
    float ticksPerSecond;
    @Shadow
    public int elapsedTicks;
    @Shadow
    public float renderPartialTicks;
    @Shadow
    public float timerSpeed;
    @Shadow
    public float elapsedPartialTicks;

    @Unique
    private final TimerCore vk$core = new TimerCore();

    /** @author NR-05 */
    @Overwrite
    public void updateTimer() {
        double delta = this.vk$core.deltaSeconds(System.nanoTime());
        this.elapsedPartialTicks = TimerCore.accumulate(this.elapsedPartialTicks, delta, this.timerSpeed, this.ticksPerSecond);
        this.elapsedTicks = (int) this.elapsedPartialTicks;
        this.elapsedPartialTicks = this.elapsedPartialTicks - this.elapsedTicks;
        if (this.elapsedTicks > 10) {
            this.elapsedTicks = 10;
        }
        this.renderPartialTicks = this.elapsedPartialTicks;
    }
}
