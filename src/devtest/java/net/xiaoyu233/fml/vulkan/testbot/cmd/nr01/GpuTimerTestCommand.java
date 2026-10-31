package net.xiaoyu233.fml.vulkan.testbot.cmd.nr01;

import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.render.gpu.DeviceFeatures;
import net.xiaoyu233.fml.vulkan.render.gpu.GpuTimer;
import net.xiaoyu233.fml.vulkan.render.state.Phase;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * {@code gpuTimerTest}: reports the device features NR-01 enabled and marks two phases around a frame's work for 30 frames, then checks
 * that {@link GpuTimer} delivers finite, non-negative times (skipped when the device has no timestamps).
 */
final class GpuTimerTestCommand implements DevCommand {
    @Override
    public String name() {
        return "gpuTimerTest";
    }

    @Override
    public String description() {
        return "device feature report and GpuTimer phase timings over 30 frames";
    }

    @Override
    public void run(Report.Section s, String[] args) throws Exception {
        if (!RenderCore.isInitialized()) {
            s.warn("gpuTimerTest", "Vulkan backend not running");
            return;
        }
        s.info("device features", DeviceFeatures.caps() + ", uma " + DeviceFeatures.uma() + ", mdi " + DeviceFeatures.mdiEnabled()
                + ", framesInFlight " + RenderCore.framesInFlight() + ", swapchain images " + RenderCore.swapchainImageCount());
        if (!DeviceFeatures.timestamps()) {
            s.warn("gpuTimerTest", "the device has no timestamp support on the graphics queue: GpuTimer is inert");
            return;
        }
        double[] sums = new double[2];
        int[] frames = {0};
        RenderTasks.run(frame -> {
            // the phases bracket the work recorded after the call: the main pass of the game
            GpuTimer.beginPhase(Phase.TERRAIN);
            GpuTimer.beginPhase(Phase.GUI);
            if (frames[0] >= RenderCore.framesInFlight() + 2) {
                sums[0] += GpuTimer.gpuFrameMs();
                sums[1] += GpuTimer.gpuMs(Phase.GUI) + GpuTimer.gpuMs(Phase.TERRAIN);
            }
            return ++frames[0] >= 30;
        }, 120);
        double frameMs = sums[0] / (30 - RenderCore.framesInFlight() - 2);
        s.check(Double.isFinite(sums[0]) && sums[0] >= 0.0 && Double.isFinite(sums[1]) && sums[1] >= 0.0, "gpuTimerTest times are finite",
                "frame " + sums[0] + ", phases " + sums[1]);
        s.info("gpuTimerTest", String.format(java.util.Locale.ROOT, "mean GPU frame time over the test frames: %.3f ms (timestamp period %.2f ns)",
                frameMs, DeviceFeatures.timestampPeriodNs()));
    }
}
