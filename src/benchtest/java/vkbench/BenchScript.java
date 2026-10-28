package vkbench;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChunkCoordinates;
import net.minecraft.ChunkPosition;
import net.minecraft.EnumOptions;
import net.minecraft.IChunkProvider;
import net.minecraft.ServerPlayer;
import net.minecraft.Village;
import net.minecraft.WorldServer;
import net.minecraft.server.MinecraftServer;
import net.xiaoyu233.fml.config.Configs;

/**
 * The linear script of the benchmark (7.3). For every selected render distance: one unmeasured warm pass over the route, then {@code fml.bench.runs} measured passes. A pass
 * visits the selected dimensions in the order Overworld, Twilight Forest, MITE Underworld, Nether; in each: arrival, 10 s warm-up (recorded as segment {@code arrival}, not
 * part of the headline numbers: it is the source of the meshing throughput and the time to an empty rebuild queue), then the measured segments.
 */
final class BenchScript {
    private static final int RUNS = Integer.getInteger("fml.bench.runs", 3);
    private static final boolean WARM = Boolean.parseBoolean(System.getProperty("fml.bench.warm", "true"));
    private static final double SCALE = Double.parseDouble(System.getProperty("fml.bench.scale", "1.0"));
    private static final List<String> DIMS = Arrays.asList(System.getProperty("fml.bench.dims", "overworld,tf,underworld,nether").toLowerCase(Locale.ROOT).split(","));
    private static final List<String> RDS = Arrays.asList(System.getProperty("fml.bench.rd", "normal").toLowerCase(Locale.ROOT).split(","));
    private static final int ARRIVAL_TICKS = ticks(10);

    private final Bench bench;
    private final List<String> notes = new ArrayList<>();
    private int originX;
    private int originZ;

    BenchScript(Bench bench) {
        this.bench = bench;
    }

    private static int ticks(double seconds) {
        return Math.max(4, (int) Math.round(seconds * BenchRoute.TPS * SCALE));
    }

    void run() throws Exception {
        try {
            runInner();
        } finally {
            // an aborted run keeps its summary of the segments finished so far
            try {
                writeSummary();
            } catch (IOException | RuntimeException e) {
                Bench.log("summary failed: " + e);
            }
        }
    }

    private void runInner() throws Exception {
        Bench.log("route scale " + SCALE + ", runs " + RUNS + ", warm pass " + WARM + ", dimensions " + DIMS + ", render distances " + RDS);
        boolean menu = bench.waitForCondition(bench::mainMenuUp, 6000);
        if (!menu) {
            Bench.log("main menu not reached: aborting");
            return;
        }
        bench.client(this::configureGame);
        bench.createWorld(Boolean.getBoolean("fml.bench.keepWorld"));
        boolean in = bench.waitForCondition(() -> bench.mc.theWorld != null && bench.mc.thePlayer != null && bench.mc.currentScreen == null, 6000);
        if (!in) {
            Bench.log("world not entered: aborting");
            return;
        }
        bench.server(this::preparePlayer);
        bench.waitTicks(40);
        double[] sp = bench.client(() -> new double[] {bench.mc.thePlayer.posX, bench.mc.thePlayer.posZ});
        originX = (int) Math.floor(sp[0]);
        originZ = (int) Math.floor(sp[1]);
        Bench.log("world entered at " + originX + "/" + originZ);
        for (String rd : RDS) {
            setRenderDistance(rd);
            if (WARM) {
                Bench.log("warm pass (" + rd + ")");
                pass(rd, "warm");
            }
            for (int run = 1; run <= RUNS; run++) {
                Bench.log("measured pass " + run + "/" + RUNS + " (" + rd + ")");
                pass(rd, Integer.toString(run));
            }
        }
    }

    // ------------------------------------------------------------------ set-up

    private void configureGame() {
        // identical settings on every build: unlimited frame rate, vsync off, no pause on focus loss, fixed GUI
        Configs.Client.FPS_LIMIT.set(9999);
        bench.mc.gameSettings.limitFramerate = 0;
        bench.mc.gameSettings.pauseOnLostFocus = false;
        bench.mc.gameSettings.showDebugInfo = false;
        bench.mc.gameSettings.viewBobbing = false;
        if (bench.mc.gameSettings.isVsyncEnabled()) {
            bench.mc.gameSettings.setOptionValue(EnumOptions.ENABLE_VSYNC, 0);
        }
    }

    /** noClip on BOTH players: the server player must not collide while the client player is teleported through terrain, or it rubber-bands the camera. */
    private void setNoClip(boolean on) throws Exception {
        bench.server(() -> bench.serverPlayer().noClip = on);
        bench.client(() -> bench.mc.thePlayer.noClip = on);
    }

    private void preparePlayer() {
        ServerPlayer p = bench.serverPlayer();
        p.setGameType(net.minecraft.EnumGameType.CREATIVE);
        p.capabilities.allowFlying = true;
        p.capabilities.isFlying = true;
        p.capabilities.disableDamage = true;
        p.sendPlayerAbilities();
        for (WorldServer w : MinecraftServer.getServer().worldServers) {
            if (w != null) {
                try {
                    w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
                    w.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
                } catch (Throwable ignored) {
                    // not fatal
                }
            }
        }
    }

    private void setRenderDistance(String rd) throws Exception {
        int target = rd.equals("far") ? 0 : rd.equals("short") ? 2 : rd.equals("tiny") ? 3 : 1;
        bench.client(() -> {
            int guard = 0;
            while (bench.mc.gameSettings.getRenderDistance() != target && guard++ < 4) {
                bench.mc.gameSettings.setOptionValue(EnumOptions.RENDER_DISTANCE, 1);
            }
            bench.mc.renderGlobal.loadRenderers();
        });
        Bench.log("render distance " + rd + " (index " + target + ")");
    }

    // ------------------------------------------------------------------ the route

    private void pass(String rd, String run) throws Exception {
        for (String dim : DIMS) {
            try {
                switch (dim) {
                    case "overworld" -> overworld(rd, run);
                    case "tf" -> twilightForest(rd, run);
                    case "underworld" -> underworld(rd, run);
                    case "nether" -> nether(rd, run);
                    default -> Bench.log("unknown dimension '" + dim + "' skipped");
                }
            } catch (SkipDimension skip) {
                String n = dim + ": " + skip.getMessage();
                if (!notes.contains(n)) {
                    notes.add(n);
                }
                Bench.log("skipped " + n);
            }
        }
    }

    private static final class SkipDimension extends Exception {
        SkipDimension(String why) {
            super(why);
        }
    }

    private int dimensionOfClient() throws Exception {
        return bench.client(() -> bench.mc.theWorld.getDimensionId());
    }

    private void enterDimension(int id, boolean sameDimensionOk) throws Exception {
        int now = dimensionOfClient();
        if (now == id && sameDimensionOk) {
            return;
        }
        bench.server(() -> bench.serverPlayer().travelToDimension(id));
        boolean arrived = bench.waitForCondition(() -> bench.mc.theWorld != null && bench.mc.thePlayer != null && bench.mc.theWorld.getDimensionId() == id, 1200);
        if (!arrived) {
            throw new IllegalStateException("did not arrive in dimension " + id);
        }
        bench.waitTicks(20);
        bench.server(this::preparePlayer);
    }

    private void teleport(double x, double y, double z, float yaw, float pitch) throws Exception {
        bench.server(() -> {
            ServerPlayer p = bench.serverPlayer();
            p.fallDistance = 0.0F;
            p.playerNetServerHandler.setPlayerLocation(x, y, z, yaw, pitch);
        });
        bench.waitForCondition(() -> Math.abs(bench.mc.thePlayer.posX - x) < 1.0 && Math.abs(bench.mc.thePlayer.posZ - z) < 1.0, 200);
        bench.client(() -> bench.mc.thePlayer.setPositionAndRotation(x, y + bench.mc.thePlayer.yOffset, z, yaw, pitch));
    }

    /** Opens the CSV of (dimension, rd, run) and records the arrival warm-up; the warm-up statistics go to the summary as segment "arrival". */
    private void segmentsOf(String dim, String rd, String run, SegmentBody body) throws Exception {
        bench.openCsv(dim, rd, run);
        try {
            body.run();
        } finally {
            bench.closeCsv();
        }
    }

    private interface SegmentBody {
        void run() throws Exception;
    }

    private void measured(String dim, String rd, String run, String segment, BenchRoute.Path path) throws Exception {
        BenchStats s = bench.beginSegment(segment);
        bench.fly(path, s);
        bench.endSegment(s);
        if (!run.equals("warm")) {
            bench.addSummary(dim + "|" + rd + "|" + run, s.summarise());
        }
        Bench.log(dim + " " + segment + " (" + run + "): " + path.describe() + " -> " + String.format(Locale.ROOT, "%.1f fps avg, %.1f fps 1%% low, %d frames", s.summarise().avgFps(), s.summarise().low1(), s.n));
    }

    /** Arrival segment: position the camera, fly an idle path for the warm-up while measuring the rebuild queue draining. */
    private void arrival(String dim, String rd, String run, double x, double y, double z, float yaw, float pitch) throws Exception {
        teleport(x, y, z, yaw, pitch);
        BenchStats s = bench.beginSegment("arrival");
        long start = System.nanoTime();
        double[] settled = {-1};
        Thread watcher = new Thread(() -> {
            // time from arrival to an empty rebuild queue that stayed empty for 20 ticks
            try {
                int quiet = 0;
                long t0 = bench.tickCount();
                while (bench.tickCount() - t0 < ARRIVAL_TICKS * 4L) {
                    Thread.sleep(25);
                    int pending = bench.pendingRebuilds();
                    quiet = pending == 0 ? quiet + 1 : 0;
                    if (quiet >= 20) {
                        settled[0] = (System.nanoTime() - start) / 1.0e9;
                        return;
                    }
                }
            } catch (InterruptedException ignored) {
                // done
            }
        }, "VkBench-settle");
        watcher.setDaemon(true);
        watcher.start();
        bench.fly(BenchRoute.idle(x, y, z, yaw, pitch, ARRIVAL_TICKS), s);
        bench.endSegment(s);
        watcher.interrupt();
        s.settleSeconds = settled[0];
        if (!run.equals("warm")) {
            bench.addSummary(dim + "|" + rd + "|" + run, s.summarise());
        }
    }

    private void overworld(String rd, String run) throws Exception {
        enterDimension(0, true);
        int top = bench.client(() -> bench.mc.theWorld.getHeightValue(originX, originZ));
        double y = 100;
        segmentsOf("overworld", rd, run, () -> {
            arrival("overworld", rd, run, originX + 0.5, y, originZ + 0.5, -90.0F, 5.0F);
            // 60 s flight, 1,500 blocks along +X, a 360 degree turn every 300 blocks
            int t = ticks(60);
            double speed = 1500.0 / (60.0 * BenchRoute.TPS);
            measured("overworld", rd, run, "flight", BenchRoute.flight(originX + 0.5, originZ + 0.5, t, speed, y, y, 5.0F, 300.0, ticks(3)));
            // 30 s cave walk at y = 30 (scripted, 0.4 blocks per tick, inside the terrain with noClip: exercises the underground visibility)
            double endX = originX + 0.5 + speed * t;
            setNoClip(true);
            arrival("overworld", rd, run, endX, 30.0, originZ + 0.5, -90.0F, 0.0F);
            measured("overworld", rd, run, "cave", BenchRoute.flight(endX, originZ + 0.5, ticks(30), 0.4, 30.0, 30.0, 0.0F, 0.0, 1));
            setNoClip(false);
            // 30 s idle in a village (nearest known village of the world, else the spawn column)
            double[] v = villageOrSpawn(top);
            arrival("overworld", rd, run, v[0], v[1], v[2], 0.0F, 10.0F);
            measured("overworld", rd, run, "idle", BenchRoute.idle(v[0], v[1], v[2], 0.0F, 10.0F, ticks(30)));
        });
    }

    private double[] villageOrSpawn(int spawnTop) throws Exception {
        return bench.server(() -> {
            WorldServer w = bench.serverWorld(0);
            Village best = null;
            double bestD = Double.MAX_VALUE;
            for (Object o : w.villageCollectionObj.getVillageList()) {
                Village v = (Village) o;
                ChunkCoordinates c = v.getCenter();
                double d = (double) c.posX * c.posX + (double) c.posZ * c.posZ;
                if (d < bestD) {
                    bestD = d;
                    best = v;
                }
            }
            if (best == null) {
                String n = "overworld idle: no village in this world, idle at the spawn column";
                if (!notes.contains(n)) {
                    notes.add(n);
                }
                return new double[] {originX + 0.5, spawnTop + 12.0, originZ + 0.5};
            }
            ChunkCoordinates c = best.getCenter();
            int top = w.getHeightValue(c.posX, c.posZ);
            return new double[] {c.posX + 0.5, top + 12.0, c.posZ + 0.5};
        });
    }

    private Integer twilightForestId() {
        try {
            return (Integer) Class.forName("twilightforest.dimension.TFDimension").getField("id").get(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private void twilightForest(String rd, String run) throws Exception {
        Integer id = twilightForestId();
        if (id == null) {
            throw new SkipDimension("Twilight Forest mod not installed");
        }
        enterDimension(id, false);
        int[] top = bench.server(() -> {
            WorldServer w = bench.serverWorld(id);
            return new int[] {w.getTopSolidOrLiquidBlock(0, 0)};
        });
        segmentsOf("tf", rd, run, () -> {
            arrival("tf", rd, run, 0.5, Math.max(top[0], 40) + 15.0, 0.5, -90.0F, 5.0F);
            int t = ticks(60);
            measured("tf", rd, run, "flight", BenchRoute.flight(0.5, 0.5, t, 1.0, 60.0, 60.0, 8.0F, 0.0, 1));
            // 30 s at a hedge maze (the mod's own locator through its command; the player is moved by the server)
            String cmd = "tf hedge_maze";
            bench.server(() -> {
                ServerPlayer p = bench.serverPlayer();
                MinecraftServer.getServer().getCommandManager().executeCommand(p, "/" + cmd, true);
            });
            bench.waitTicks(60);
            double[] pos = bench.client(() -> new double[] {bench.mc.thePlayer.posX, bench.mc.thePlayer.boundingBox.minY, bench.mc.thePlayer.posZ});
            arrival("tf", rd, run, pos[0], pos[1] + 6.0, pos[2], 0.0F, 25.0F);
            measured("tf", rd, run, "idle", BenchRoute.idle(pos[0], pos[1] + 6.0, pos[2], 0.0F, 25.0F, ticks(30)));
        });
    }

    private void underworld(String rd, String run) throws Exception {
        enterDimension(-2, false);
        segmentsOf("underworld", rd, run, () -> {
            arrival("underworld", rd, run, 0.5, 100.0, 0.5, -90.0F, 5.0F);
            int t = ticks(60);
            double speed = 1.0;
            setNoClip(true);
            measured("underworld", rd, run, "flight", BenchRoute.flight(0.5, 0.5, t, speed, 100.0, 40.0, 5.0F, 0.0, 1));
            double endX = 0.5 + speed * t;
            arrival("underworld", rd, run, endX, 40.0, 0.5, -90.0F, 5.0F);
            measured("underworld", rd, run, "idle", BenchRoute.idle(endX, 40.0, 0.5, -90.0F, 5.0F, ticks(30)));
            setNoClip(false);
        });
    }

    private void nether(String rd, String run) throws Exception {
        enterDimension(-1, false);
        segmentsOf("nether", rd, run, () -> {
            arrival("nether", rd, run, 0.5, 64.0, 0.5, -90.0F, 5.0F);
            int t = ticks(60);
            double speed = 1.0;
            measured("nether", rd, run, "flight", BenchRoute.flight(0.5, 0.5, t, speed, 64.0, 64.0, 5.0F, 0.0, 1));
            double endX = 0.5 + speed * t;
            double[] fortress = bench.server(() -> {
                WorldServer w = bench.serverWorld(-1);
                IChunkProvider cp = w.getChunkProvider();
                try {
                    Object gen = cp.getClass().getField("genNetherBridge").get(cp);
                    ChunkPosition pos = (ChunkPosition) gen.getClass().getMethod("getNearestInstance", net.minecraft.World.class, int.class, int.class, int.class)
                        .invoke(gen, w, (int) endX, 64, 0);
                    if (pos != null) {
                        return new double[] {pos.x + 0.5, 70.0, pos.z + 0.5};
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // chunk provider without a bridge generator
                }
                return null;
            });
            double fx = fortress != null ? fortress[0] : endX;
            double fy = fortress != null ? fortress[1] : 64.0;
            double fz = fortress != null ? fortress[2] : 0.5;
            if (fortress == null) {
                String n = "nether idle: no fortress found near the flight end, idle at the flight end";
                if (!notes.contains(n)) {
                    notes.add(n);
                }
            }
            arrival("nether", rd, run, fx, fy, fz, 0.0F, 15.0F);
            measured("nether", rd, run, "idle", BenchRoute.idle(fx, fy, fz, 0.0F, 15.0F, ticks(30)));
        });
    }

    // ------------------------------------------------------------------ summary

    private static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        s.removeIf(d -> d == null || Double.isNaN(d));
        if (s.isEmpty()) {
            return Double.NaN;
        }
        java.util.Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : 0.5 * (s.get(n / 2 - 1) + s.get(n / 2));
    }

    private static String stat(List<Double> v, String fmt) {
        List<Double> s = new ArrayList<>(v);
        s.removeIf(d -> d == null || Double.isNaN(d) || d < 0);
        if (s.isEmpty()) {
            return "n/a";
        }
        double lo = java.util.Collections.min(s);
        double hi = java.util.Collections.max(s);
        return String.format(Locale.ROOT, fmt, median(s)) + " (" + String.format(Locale.ROOT, fmt, lo) + "-" + String.format(Locale.ROOT, fmt, hi) + ")";
    }

    private void writeSummary() throws IOException {
        File dir = bench.outDir;
        StringBuilder csv = new StringBuilder("build,dimension,rd,run," + BenchStats.Summary.header() + "\n");
        Map<String, List<BenchStats.Summary>> groups = new LinkedHashMap<>();
        List<String> keys = bench.summaryKeys();
        List<BenchStats.Summary> all = bench.summaries();
        for (int i = 0; i < keys.size(); i++) {
            String[] k = keys.get(i).split("\\|");
            csv.append(Bench.BUILD).append(',').append(k[0]).append(',').append(k[1]).append(',').append(k[2]).append(',').append(all.get(i).csv()).append('\n');
            groups.computeIfAbsent(k[0] + "|" + k[1] + "|" + all.get(i).segment(), x -> new ArrayList<>()).add(all.get(i));
        }
        Files.writeString(new File(dir, "summary.csv").toPath(), csv.toString(), StandardCharsets.UTF_8);

        StringBuilder md = new StringBuilder();
        md.append("# Bench summary - ").append(Bench.TAG).append(" (build ").append(Bench.BUILD).append(")\n\n");
        md.append("Median over ").append(RUNS).append(" measured run(s) with min-max in parentheses. Frame times in ms. `arrival` is the 10 s warm-up after arriving in a dimension (not part of the ")
            .append("headline numbers; its chunk updates and the settle time give the meshing throughput and the time to an empty rebuild queue).\n\n");
        md.append("| dimension | rd | segment | avg FPS | 1% low FPS | 0.1% low FPS | frame ms stddev | p99 ms | max ms | heap max MB | RSS max MB | VMA max MB | GC count | GC ms | chunk updates | settle s |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, List<BenchStats.Summary>> e : groups.entrySet()) {
            String[] k = e.getKey().split("\\|");
            List<BenchStats.Summary> g = e.getValue();
            md.append("| ").append(k[0]).append(" | ").append(k[1]).append(" | ").append(k[2]).append(" | ");
            md.append(stat(col(g, BenchStats.Summary::avgFps), "%.1f")).append(" | ").append(stat(col(g, BenchStats.Summary::low1), "%.1f")).append(" | ");
            md.append(stat(col(g, BenchStats.Summary::low01), "%.1f")).append(" | ").append(stat(col(g, BenchStats.Summary::stddevMs), "%.2f")).append(" | ");
            md.append(stat(col(g, BenchStats.Summary::p99), "%.2f")).append(" | ").append(stat(col(g, BenchStats.Summary::maxMs), "%.1f")).append(" | ");
            md.append(stat(col(g, s -> (double) s.heapMaxMb()), "%.0f")).append(" | ").append(stat(col(g, s -> (double) s.rssMaxMb()), "%.0f")).append(" | ");
            md.append(stat(col(g, s -> (double) s.vmaMaxMb()), "%.0f")).append(" | ").append(stat(col(g, s -> (double) s.gcCount()), "%.0f")).append(" | ");
            md.append(stat(col(g, s -> (double) s.gcMs()), "%.0f")).append(" | ").append(stat(col(g, s -> (double) s.chunkUpdates()), "%.0f")).append(" | ");
            md.append(stat(col(g, BenchStats.Summary::settleSeconds), "%.1f")).append(" |\n");
        }
        md.append("\n## Environment\n\n");
        md.append("- build: ").append(Bench.BUILD).append(", tag ").append(Bench.TAG).append("\n");
        md.append("- JVM: ").append(System.getProperty("java.vendor")).append(' ').append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vm.name"))
            .append("), os.arch ").append(System.getProperty("os.arch")).append(", ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append("\n");
        md.append("- JVM flags: ").append(String.join(" ", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments())).append("\n");
        md.append("- cores: ").append(Runtime.getRuntime().availableProcessors()).append(", max heap ").append(Runtime.getRuntime().maxMemory() >> 20).append(" MB\n");
        md.append("- frame limits: ").append(limits()).append("\n");
        md.append("- seed ").append(Bench.SEED).append(", route scale ").append(SCALE).append(", dimensions ").append(DIMS).append(", render distances ").append(RDS).append("\n");
        List<String> warns = displayBoundWarnings(groups);
        if (!warns.isEmpty()) {
            md.append("\n## WARNING: display bound\n\n");
            for (String w : warns) {
                md.append("- ").append(w).append('\n');
            }
            md.append("\nThese numbers are limited by the display or a frame cap, not by the renderer; do not compare them with an uncapped build.\n");
        }
        if (!notes.isEmpty()) {
            md.append("\n## Notes\n\n");
            for (String n : notes) {
                md.append("- ").append(n).append('\n');
            }
        }
        Files.writeString(new File(dir, "summary.md").toPath(), md.toString(), StandardCharsets.UTF_8);
        Bench.log("summary written to " + new File(dir, "summary.md"));
    }

    private static String limits() {
        StringBuilder b = new StringBuilder();
        String[] keys = {"vsync", "presentMode", "capUnfocused", "capMinimised"};
        for (String k : keys) {
            String v = System.getProperty("fml.vulkan." + k);
            b.append(k).append('=').append(v == null ? "(default)" : v).append(", ");
        }
        try {
            Class<?> c = Class.forName("net.xiaoyu233.fml.vulkan.VulkanConfig");
            b.append("effective presentMode=").append(c.getMethod("presentMode").invoke(null)).append(", capUnfocused=").append(c.getMethod("capUnfocused").invoke(null))
                .append(", capMinimised=").append(c.getMethod("capMinimised").invoke(null)).append(", ");
        } catch (ReflectiveOperationException | LinkageError e) {
            b.append("(no VulkanConfig: OpenGL baseline), ");
        }
        b.append("FPS_LIMIT 9999, in-game limitFramerate 0");
        return b.toString();
    }

    /** Segments whose median frame time sits within 5% of a refresh period (or of 33.3 ms, the unfocused cap): the run was display or cap bound. */
    private static List<String> displayBoundWarnings(Map<String, List<BenchStats.Summary>> groups) {
        List<Double> rates = new ArrayList<>(List.of(30.0, 60.0, 75.0, 90.0, 120.0, 144.0, 165.0, 240.0));
        try {
            int[] mode = (int[]) Class.forName("net.xiaoyu233.fml.vulkan.window.NativeWindow").getMethod("desktopMode").invoke(null);
            if (mode != null && mode.length > 2 && mode[2] > 0) {
                rates.add(0, (double) mode[2]);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            // baseline: the common rates stand in
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<BenchStats.Summary>> e : groups.entrySet()) {
            if (e.getKey().endsWith("|arrival")) {
                continue;
            }
            double p50 = median(col(e.getValue(), BenchStats.Summary::p50));
            double p95 = median(col(e.getValue(), BenchStats.Summary::p95));
            if (Double.isNaN(p50) || p50 <= 0 || p95 > p50 * 1.5) {
                continue;
            }
            for (double hz : rates) {
                if (Math.abs(p50 - 1000.0 / hz) <= 0.05 * 1000.0 / hz) {
                    out.add(e.getKey().replace('|', ' ') + ": p50 frame time " + String.format(Locale.ROOT, "%.2f", p50) + " ms is within 5% of " + String.format(Locale.ROOT, "%.2f", 1000.0 / hz) + " ms (" + (int) hz + " Hz)");
                    break;
                }
            }
        }
        return out;
    }

    private static List<Double> col(List<BenchStats.Summary> g, java.util.function.ToDoubleFunction<BenchStats.Summary> f) {
        List<Double> out = new ArrayList<>();
        for (BenchStats.Summary s : g) {
            out.add(f.applyAsDouble(s));
        }
        return out;
    }
}
