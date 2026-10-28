package net.xiaoyu233.fml.vulkan.testbot.cmd.nr10;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.Block;
import net.minecraft.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.World;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshResult;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshTask;
import net.xiaoyu233.fml.vulkan.render.mesh.SectionSnapshot;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * {@code flowerPot}: places flower pots with contents (metadata 1..11) near the player, meshes their sections on a worker and on the render
 * thread, and asserts that the metadata of the live client world (and the server world) is unchanged afterwards; the pots must be
 * present in the meshed section (non-empty mesh). The pots are removed at the end.
 */
final class FlowerPotCommand implements DevCommand {
    @Override
    public String name() {
        return "flowerPot";
    }

    @Override
    public String description() {
        return "metadata of the live world unchanged after meshing sections with flower pots";
    }

    @Override
    public void run(Report.Section section, String[] args) throws Exception {
        TestBot bot = Hooks.bot();
        Minecraft mc = bot.minecraft();
        Hooks.ensureWorkers(2);
        int[] base = bot.client(() -> new int[] {(int) Math.floor(mc.thePlayer.posX), (int) Math.floor(mc.thePlayer.posY), (int) Math.floor(mc.thePlayer.posZ),
                mc.theWorld.getDimensionId()});
        List<int[]> pots = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            pots.add(new int[] {base[0] + 2 + i % 4, base[1] + 4, base[2] + 2 + i / 4, i + 1});
        }
        try {
            bot.server(() -> {
                World sw = MinecraftServer.getServer().worldServerForDimension(base[3]);
                for (int[] p : pots) {
                    sw.setBlock(p[0], p[1], p[2], Block.flowerPot.blockID, p[3], 3);
                }
            });
            bot.ticks(10);
            int present = bot.client(() -> {
                int n = 0;
                for (int[] p : pots) {
                    if (mc.theWorld.getBlockId(p[0], p[1], p[2]) == Block.flowerPot.blockID) {
                        n++;
                    }
                }
                return n;
            });
            if (present == 0) {
                section.warn("flowerPot", "no pots arrived on the client; cannot test");
                return;
            }
            int[] before = bot.client(() -> snapshotMeta(mc.theWorld, pots));
            int[] serverBefore = bot.server(() -> snapshotMeta(MinecraftServer.getServer().worldServerForDimension(base[3]), pots));
            List<Hooks.Sec> secs = bot.client(() -> {
                List<Hooks.Sec> all = Hooks.pickSections(mc, 100000, 1L);
                List<Hooks.Sec> hit = new ArrayList<>();
                for (Hooks.Sec s : all) {
                    for (int[] p : pots) {
                        if (p[0] >= s.x() && p[0] < s.x() + 16 && p[1] >= s.y() && p[1] < s.y() + 16 && p[2] >= s.z() && p[2] < s.z() + 16) {
                            hit.add(s);
                            break;
                        }
                    }
                }
                return hit;
            });
            section.check(!secs.isEmpty(), "flowerPot sections found", "no renderer section contains a pot");
            for (int round = 0; round < 3; round++) {
                List<MeshTask> tasks = new ArrayList<>();
                List<MeshResult> refs = new ArrayList<>();
                final int r = round;
                bot.client(() -> {
                    for (int i = 0; i < secs.size(); i++) {
                        tasks.add(Hooks.task(secs.get(i), r * 100 + i, Hooks.capture(mc, secs.get(i), -1)));
                        refs.add(Hooks.buildOnCallingThread(Hooks.task(secs.get(i), r * 100 + i, Hooks.capture(mc, secs.get(i), -1))));
                    }
                    return null;
                });
                var results = Hooks.runOnWorkers(tasks, 30_000);
                for (int i = 0; i < tasks.size(); i++) {
                    MeshResult w = results.get(tasks.get(i).version());
                    section.check(w != null, "flowerPot worker result", "no worker result");
                    if (w != null) {
                        String d = Hooks.diff(w, refs.get(i));
                        section.check(d == null, "flowerPot worker == render thread mesh", d);
                        w.release();
                    }
                    refs.get(i).release();
                }
            }
            int[] after = bot.client(() -> snapshotMeta(mc.theWorld, pots));
            int[] serverAfter = bot.server(() -> snapshotMeta(MinecraftServer.getServer().worldServerForDimension(base[3]), pots));
            section.check(java.util.Arrays.equals(before, after), "flowerPot client metadata unchanged", java.util.Arrays.toString(before) + " -> " + java.util.Arrays.toString(after));
            section.check(java.util.Arrays.equals(serverBefore, serverAfter), "flowerPot server metadata unchanged",
                    java.util.Arrays.toString(serverBefore) + " -> " + java.util.Arrays.toString(serverAfter));
            TestBot.log("flowerPot clientMetaUnchanged=" + java.util.Arrays.equals(before, after) + " pots=" + present);
        } finally {
            bot.server(() -> {
                World sw = MinecraftServer.getServer().worldServerForDimension(base[3]);
                for (int[] p : pots) {
                    sw.setBlock(p[0], p[1], p[2], 0, 0, 3);
                }
            });
        }
    }

    private static int[] snapshotMeta(World w, List<int[]> pots) {
        int[] out = new int[pots.size() * 2];
        for (int i = 0; i < pots.size(); i++) {
            int[] p = pots.get(i);
            out[i * 2] = w.getBlockId(p[0], p[1], p[2]);
            out[i * 2 + 1] = w.getBlockMetadata(p[0], p[1], p[2]);
        }
        return out;
    }
}
