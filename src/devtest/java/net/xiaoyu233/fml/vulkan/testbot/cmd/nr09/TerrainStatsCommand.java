package net.xiaoyu233.fml.vulkan.testbot.cmd.nr09;

import java.lang.reflect.Method;

import net.xiaoyu233.fml.vulkan.render.frame.FrameStats;
import net.xiaoyu233.fml.vulkan.render.terrain.SectionScheduler;
import net.xiaoyu233.fml.vulkan.render.terrain.TerrainRenderer;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * {@code nr09.terrainStats}: prints {@link TerrainRenderer#debugInfo()} (visible / total sections and the frustum, occlusion and empty
 * culling counters), the visible count parsed from it, the rebuild queue size, and the draw counts of the latest frame from
 * {@link FrameStats#latest()}. {@code SectionScheduler.queued()} is package-private, so the queue size is read reflectively for this
 * devtest command only; if the read fails it is reported as unavailable.
 */
final class TerrainStatsCommand implements DevCommand {
    @Override
    public String name() {
        return "nr09.terrainStats";
    }

    @Override
    public String description() {
        return "TerrainRenderer.debugInfo, visible section count, latest frame draws / encoded draws";
    }

    @Override
    public void run(Report.Section s, String[] args) throws Exception {
        String info = TerrainRenderer.debugInfo();
        s.info("debugInfo", info);
        s.info("visible sections", visibleCount(info));
        s.info("queue size", queueSize());
        FrameStats.Frame f = FrameStats.latest();
        s.info("latest frame", "index " + f.index() + ", draws " + f.draws() + ", encodedDraws " + f.encodedDraws()
                + ", sectionsVisible " + f.sectionsVisible());
    }

    /** Reads {@code SectionScheduler.queued()} reflectively (package-private); returns an "unavailable" note if that fails. */
    static String queueSize() {
        try {
            Method m = SectionScheduler.class.getDeclaredMethod("queued");
            m.setAccessible(true);
            return String.valueOf(m.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "unavailable (" + e + ")";
        }
    }

    /** Parses the {@code C: visible/total} head of {@code debugInfo}; returns the raw text when the format differs. */
    static String visibleCount(String info) {
        int c = info.indexOf("C: ");
        if (c < 0) {
            return "unparsed";
        }
        int end = info.indexOf('.', c);
        return end < 0 ? "unparsed" : info.substring(c + 3, end).trim();
    }
}
