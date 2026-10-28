package net.xiaoyu233.fml.vulkan.testbot.cmd.nr09;

import net.xiaoyu233.fml.vulkan.render.terrain.TerrainRenderer;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * {@code nr09.translucentOrder}: reports the culling state the translucent pass depends on, via the public {@link TerrainRenderer#debugInfo()}.
 *
 * <p>TODO(NR-09 sonnet reviewer): the real distance-order test (TranslucentOrder.build sorted farthest first, stable on bfsIndex, and the
 * resort budget MAX_RESORTS_PER_FRAME) must be added here once {@code TranslucentOrder} exposes its order publicly. Until then this command
 * only checks that the visibility data is present.
 */
final class TranslucentOrderCommand implements DevCommand {
    @Override
    public String name() {
        return "nr09.translucentOrder";
    }

    @Override
    public String description() {
        return "translucent order precondition: TerrainRenderer.debugInfo (distance-order check pending, see TODO)";
    }

    @Override
    public void run(Report.Section s, String[] args) throws Exception {
        String info = TerrainRenderer.debugInfo();
        s.info("debugInfo", info);
        s.info("distance order", "not checked: TranslucentOrder is not public yet (TODO)");
        s.check(info != null && !info.isEmpty(), "visibility data present", "debugInfo returned no data");
    }
}
