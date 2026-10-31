package net.xiaoyu233.fml.vulkan.testbot.cmd.nr10;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.Block;
import net.minecraft.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.ServerPlayer;
import net.minecraft.World;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshResult;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshTask;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshWorkers;
import net.xiaoyu233.fml.vulkan.render.mesh.SectionSnapshot;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * {@code stress [seconds=60] [workers=8] [loadRenderers=10] [reloads=3] [dimension=1]} and {@code workerStress} (the same run with 15 s,
 * 4 workers, 2 loadRenderers, 1 reload, no dimension change). Every tick one block of the world next to the player is edited and a
 * snapshot of a loaded section is submitted; results are polled continuously. Asserts: no exception, no result delivered whose epoch is
 * older than the epoch read before polling, every worker thread joined after {@code MeshWorkers.stop()}.
 */
final class StressCommand implements DevCommand {
    private final boolean light;

    StressCommand(boolean light) {
        this.light = light;
    }

    @Override
    public String name() {
        return light ? "workerStress" : "stress";
    }

    @Override
    public String description() {
        return light ? "short worker stress (15 s, 4 workers, 2 loadRenderers, 1 reload)"
                : "8 workers, block edit per tick for 60 s, 10 loadRenderers, 3 resource reloads, 1 dimension change";
    }

    @Override
    public void run(Report.Section section, String[] args) throws Exception {
        int seconds = arg(args, 0, light ? 15 : 60);
        int workers = arg(args, 1, light ? 4 : 8);
        int loads = arg(args, 2, light ? 2 : 10);
        int reloads = arg(args, 3, light ? 1 : 3);
        int dims = arg(args, 4, light ? 0 : 1);
        TestBot bot = Hooks.bot();
        Minecraft mc = bot.minecraft();
        Hooks.ensureWorkers(workers);
        AtomicInteger submitted = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();
        AtomicInteger stale = new AtomicInteger();
        List<String> errors = new ArrayList<>();
        Random rnd = new Random(99L);
        long start = System.currentTimeMillis();
        long end = start + seconds * 1000L;
        long total = end - start;
        int loadsDone = 0;
        int reloadsDone = 0;
        int dimsDone = 0;
        int version = 0;
        long startEpoch = MeshWorkers.epoch();
        try {
            while (System.currentTimeMillis() < end && !bot.isAborted()) {
                long elapsed = System.currentTimeMillis() - start;
                final int editSeed = rnd.nextInt();
                // events spread evenly over the run
                boolean doLoad = loadsDone < loads && elapsed >= total * (loadsDone + 1) / (loads + 1);
                boolean doReload = reloadsDone < reloads && elapsed >= total * (2 * reloadsDone + 1) / (2 * reloads + 1);
                boolean doDim = dimsDone < dims && elapsed >= total * 3 / 4;
                if (doDim) {
                    dimsDone++;
                    changeDimension(bot, mc);
                } else if (doReload) {
                    reloadsDone++;
                    bot.client(() -> {
                        mc.refreshResources();
                        return null;
                    });
                } else if (doLoad) {
                    loadsDone++;
                    bot.client(() -> {
                        mc.renderGlobal.loadRenderers();
                        return null;
                    });
                }
                bot.server(() -> editBlock(editSeed));
                final int v0 = version;
                int n = bot.client(() -> {
                    List<Hooks.Sec> secs = Hooks.pickSections(mc, 6, editSeed);
                    int k = 0;
                    for (Hooks.Sec s : secs) {
                        SectionSnapshot snap = SectionSnapshot.capture(mc.theWorld, s.x(), s.y(), s.z());
                        MeshWorkers.submit(new MeshTask(s.renderer(), v0 + k, snap, mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, false), k);
                        k++;
                    }
                    return k;
                });
                version += n;
                submitted.addAndGet(n);
                bot.client(() -> {
                    long epochBefore = MeshWorkers.epoch();
                    MeshResult r;
                    while ((r = MeshWorkers.pollResult()) != null) {
                        delivered.incrementAndGet();
                        if (r.epoch() < epochBefore) {
                            stale.incrementAndGet();
                        }
                        r.release();
                    }
                    return null;
                });
                bot.ticks(1);
            }
        } catch (Throwable t) {
            errors.add(String.valueOf(t));
            section.exception("stress loop", t);
        }
        // finish pending events the run did not reach (short runs)
        section.check(errors.isEmpty(), "stress no exception", String.join("; ", errors));
        section.check(stale.get() == 0, "stress no result committed across an epoch", stale.get() + " stale results delivered");
        section.info("stress", seconds + " s, workers=" + workers + ", submitted=" + submitted + ", delivered=" + delivered + ", loadRenderers="
                + loadsDone + "/" + loads + ", reloads=" + reloadsDone + "/" + reloads + ", dimension changes=" + dimsDone + "/" + dims
                + ", epoch " + startEpoch + " -> " + MeshWorkers.epoch());
        MeshWorkers.stop();
        List<String> alive = new ArrayList<>();
        long joinEnd = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < joinEnd) {
            alive = Hooks.liveWorkerThreads();
            if (alive.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }
        section.check(alive.isEmpty(), "stress all workers joined at shutdown", "alive: " + alive);
        TestBot.log((errors.isEmpty() && stale.get() == 0 && alive.isEmpty() ? "PASS " : "FAIL ") + name() + " submitted=" + submitted + " delivered="
                + delivered + " stale=" + stale + " alive=" + alive.size());
    }

    private static void changeDimension(TestBot bot, Minecraft mc) throws Exception {
        int dim = bot.client(() -> mc.theWorld.getDimensionId());
        int target = dim == 0 ? -1 : 0;
        bot.server(() -> {
            ServerPlayer p = (ServerPlayer) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
            p.travelToDimension(target);
        });
        bot.waitFor(() -> mc.theWorld != null && mc.theWorld.getDimensionId() == target, 600);
        bot.ticks(40);
    }

    private static void editBlock(int seed) {
        MinecraftServer srv = MinecraftServer.getServer();
        ServerPlayer p = (ServerPlayer) srv.getConfigurationManager().playerEntityList.get(0);
        World w = srv.worldServerForDimension(p.dimension);
        Random r = new Random(seed);
        int x = (int) Math.floor(p.posX) + r.nextInt(33) - 16;
        int z = (int) Math.floor(p.posZ) + r.nextInt(33) - 16;
        int y = Math.max(1, Math.min(250, (int) Math.floor(p.posY) + r.nextInt(17) - 8));
        int id = w.getBlockId(x, y, z);
        w.setBlock(x, y, z, id == 0 ? Block.glass.blockID : 0, 0, 3);
    }

    private static int arg(String[] a, int i, int def) {
        return a.length > i ? Integer.parseInt(a[i]) : def;
    }
}
