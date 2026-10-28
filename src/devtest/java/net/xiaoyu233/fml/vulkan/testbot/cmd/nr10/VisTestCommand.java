package net.xiaoyu233.fml.vulkan.testbot.cmd.nr10;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.Block;
import net.minecraft.Minecraft;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshResult;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshTask;
import net.xiaoyu233.fml.vulkan.render.mesh.SectionSnapshot;
import net.xiaoyu233.fml.vulkan.render.mesh.VisGraph;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * {@code visTest [sections=300]}: the visibility mask a worker computes for a section equals an independent evaluation of
 * {@code VisGraph.compute(boolean[])} over the opaque cubes read straight from the live world, and the degenerate cases hold
 * (all air connects every face pair, all opaque connects none).
 */
final class VisTestCommand implements DevCommand {
    @Override
    public String name() {
        return "visTest";
    }

    @Override
    public String description() {
        return "worker visibility mask vs VisGraph over the live world's opaque cubes; all-air / all-opaque cases";
    }

    @Override
    public void run(Report.Section section, String[] args) throws Exception {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        TestBot bot = Hooks.bot();
        Minecraft mc = bot.minecraft();
        Hooks.ensureWorkers(4);
        section.check(VisGraph.compute(new boolean[4096]) == VisGraph.FULL, "visTest all air == FULL", "mask differs");
        boolean[] solid = new boolean[4096];
        java.util.Arrays.fill(solid, true);
        section.check(VisGraph.compute(solid) == 0L, "visTest all opaque == 0", "mask differs");
        List<Hooks.Sec> secs = bot.client(() -> Hooks.pickSections(mc, count, 4242L));
        List<MeshTask> tasks = new ArrayList<>();
        List<Long> expected = new ArrayList<>();
        bot.client(() -> {
            for (int i = 0; i < secs.size(); i++) {
                Hooks.Sec s = secs.get(i);
                boolean[] opaque = new boolean[4096];
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            opaque[y << 8 | z << 4 | x] = Block.opaqueCubeLookup[mc.theWorld.getBlockId(s.x() + x, s.y() + y, s.z() + z) & 4095];
                        }
                    }
                }
                expected.add(VisGraph.compute(opaque));
                SectionSnapshot snap = SectionSnapshot.capture(mc.theWorld, s.x(), s.y(), s.z());
                tasks.add(Hooks.task(s, i, snap));
            }
            return null;
        });
        var results = Hooks.runOnWorkers(tasks, 60_000);
        int bad = 0;
        for (int i = 0; i < tasks.size(); i++) {
            MeshResult r = results.get(i);
            if (r == null) {
                bad++;
                section.fail("visTest section " + i, "no worker result");
                continue;
            }
            if (r.visibility() != expected.get(i)) {
                bad++;
                if (bad <= 20) {
                    section.fail("visTest section " + secs.get(i).x() + "," + secs.get(i).y() + "," + secs.get(i).z(),
                            Long.toHexString(r.visibility()) + " vs " + Long.toHexString(expected.get(i)));
                }
            }
            r.release();
        }
        section.check(bad == 0, "visTest " + tasks.size() + " sections", bad + " masks differ");
        TestBot.log((bad == 0 ? "PASS" : "FAIL") + " visTest sections=" + tasks.size() + " bad=" + bad);
    }
}
