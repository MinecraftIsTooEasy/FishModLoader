package net.xiaoyu233.fml.vulkan.testbot.cmd.nr10;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.Minecraft;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshResult;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshTask;
import net.xiaoyu233.fml.vulkan.render.mesh.SectionSnapshot;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * {@code meshCompare [sections=2000] [dusk=200]}: per section three meshes of the same world state, compared byte for byte and by tile entity
 * identity/order and translucent centroids: the worker mesh against the render-thread build of the same snapshot, and the worker mesh against
 * MITE's own WorldRenderer.updateRenderer path (ChunkCache, border 1, RenderBlocks, MESH sink, one thread). A second pass at dusk
 * (skylightSubtracted 1..10 set on the world during capture); a third in the Nether when the current world has no sky, else SKIPPED.
 */
final class MeshCompareCommand implements DevCommand {
    @Override
    public String name() {
        return "meshCompare";
    }

    @Override
    public String description() {
        return "worker mesh == render-thread mesh and == MITE WorldRenderer.updateRenderer (ChunkCache path), 2000 sections, 200 at dusk, Nether";
    }

    @Override
    public void run(Report.Section section, String[] args) throws Exception {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        int dusk = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        TestBot bot = Hooks.bot();
        Minecraft mc = bot.minecraft();
        Hooks.ensureWorkers(4);
        boolean nether = bot.client(() -> mc.theWorld != null && mc.theWorld.provider.hasNoSky);
        if (nether) {
            pass(section, "meshCompare nether", count, -1, bot, mc);
        } else {
            pass(section, "meshCompare day", count, -1, bot, mc);
            pass(section, "meshCompare dusk", dusk, 1, bot, mc);
            section.info("meshCompare nether", "SKIPPED: current dimension has sky; run again after entering the Nether");
        }
    }

    /** skylightMode: -1 world value, 1 = cycle 1..10 across sections. */
    private static void pass(Report.Section section, String label, int count, int skylightMode, TestBot bot, Minecraft mc) throws Exception {
        List<Hooks.Sec> secs = bot.client(() -> Hooks.pickSections(mc, count, 20261027L + count));
        if (secs.isEmpty()) {
            section.warn(label, "no sections with loaded chunks");
            return;
        }
        int bad = 0;
        int badMite = 0;
        int done = 0;
        int batchSize = 64;
        for (int from = 0; from < secs.size(); from += batchSize) {
            List<Hooks.Sec> batch = secs.subList(from, Math.min(secs.size(), from + batchSize));
            final int base = from;
            List<MeshTask> tasks = new ArrayList<>();
            List<MeshResult> reference = new ArrayList<>();
            List<Hooks.MiteMesh> mite = new ArrayList<>();
            List<Hooks.Sec> used = new ArrayList<>();
            bot.client(() -> {
                for (int i = 0; i < batch.size(); i++) {
                    int sky = skylightMode < 0 ? -1 : 1 + (base + i) % 10;
                    SectionSnapshot snap = Hooks.capture(mc, batch.get(i), sky);
                    // one snapshot per mesh: the render-thread build and the worker build read identical data
                    SectionSnapshot snap2 = Hooks.capture(mc, batch.get(i), sky);
                    reference.add(Hooks.buildOnCallingThread(Hooks.task(batch.get(i), base + i, snap)));
                    tasks.add(Hooks.task(batch.get(i), base + i, snap2));
                    mite.add(Hooks.buildMite(mc, batch.get(i), sky));
                    used.add(batch.get(i));
                }
                return null;
            });
            var results = Hooks.runOnWorkers(tasks, 60_000);
            for (int i = 0; i < tasks.size(); i++) {
                MeshResult w = results.get(tasks.get(i).version());
                MeshResult r = reference.get(i);
                Hooks.MiteMesh m = mite.get(i);
                Hooks.Sec s = used.get(i);
                String where = "section " + s.x() + "," + s.y() + "," + s.z();
                if (w == null) {
                    bad++;
                    badMite++;
                    section.fail(label + " " + where, "no worker result within 60 s");
                } else {
                    String d = Hooks.diff(w, r);
                    if (d != null) {
                        bad++;
                        if (bad <= 20) {
                            section.fail(label + " " + where, d);
                        }
                    }
                    String dm = Hooks.diffMite(w, m);
                    if (dm != null) {
                        badMite++;
                        if (badMite <= 20) {
                            section.fail(label + " " + where, dm);
                        }
                    }
                    w.release();
                }
                r.release();
                done++;
            }
        }
        section.check(bad == 0, label + " " + done + " sections: worker == render-thread mesh", bad + " of " + done + " sections differ");
        section.check(badMite == 0, label + " " + done + " sections: worker == MITE WorldRenderer mesh", badMite + " of " + done + " sections differ");
        TestBot.log((bad == 0 && badMite == 0 ? "PASS " : "FAIL ") + label + " sections=" + done + " bad=" + bad + " badMite=" + badMite);
    }
}
