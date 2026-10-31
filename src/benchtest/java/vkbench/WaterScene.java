package vkbench;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.BiomeGenBase;
import net.minecraft.ChunkPosition;
import net.minecraft.EnumGameType;
import net.minecraft.ServerPlayer;
import net.minecraft.WorldServer;
import net.minecraft.server.MinecraftServer;

/**
 * Dev only: deterministic water capture scenes (built pool across chunk / section borders, bucket water on flat stone, a river shore and the ocean,
 * above and under water). Active with {@code -Dfml.vulkan.waterScene=<output dir>}; runs unchanged on the OpenGL baseline and the native build.
 */
final class WaterScene {
    private final Bench bench;
    private final File out;

    WaterScene(Bench bench, File out) {
        this.bench = bench;
        this.out = out;
    }

    private record Shot(String name, double x, double y, double z, float yaw, float pitch) {
    }

    void run() throws Exception {
        out.mkdirs();
        if (!bench.waitForCondition(bench::mainMenuUp, 6000)) {
            Bench.log("water: main menu not reached");
            return;
        }
        bench.client(() -> {
            bench.mc.gameSettings.pauseOnLostFocus = false;
            bench.mc.gameSettings.showDebugInfo = false;
            bench.mc.gameSettings.viewBobbing = false;
            bench.mc.gameSettings.clouds = false;
            bench.mc.gameSettings.particleSetting = 2;
        });
        bench.createWorld(false, "vkwater");
        if (!bench.waitForCondition(() -> bench.mc.theWorld != null && bench.mc.thePlayer != null && bench.mc.currentScreen == null, 6000)) {
            Bench.log("water: world not entered");
            return;
        }
        bench.server(this::preparePlayer);
        bench.waitTicks(40);
        bench.client(() -> {
            bench.mc.gameSettings.gui_mode = 2;
            bench.mc.thePlayer.noClip = true;
        });
        bench.server(() -> bench.serverPlayer().noClip = true);
        double[] sp = bench.client(() -> new double[] {bench.mc.thePlayer.posX, bench.mc.thePlayer.posZ});
        // fixed origin: MITE picks the spawn column with an unseeded RNG, the scene must be identical on every build
        int ox = 73, oz = 303;
        int cx = ((ox >> 4) << 4) + 48, cz = ((oz >> 4) << 4) + 48; // a chunk corner
        int bx = cx + 48, bz = cz;
        Bench.log("water: origin " + ox + "/" + oz + ", pool centre " + cx + "/" + cz + ", bucket " + bx + "/" + bz);

        // ---- pool: stone box, mixed floor at y 157, water 158..161 (crosses the y=160 section border and the chunk corner cx/cz)
        bench.server(() -> {
            WorldServer w = bench.serverWorld(0);
            int[] floor = {1, 12, 13, 3, 82, 1, 24};
            for (int x = cx - 8; x <= cx + 8; x++) {
                for (int z = cz - 8; z <= cz + 8; z++) {
                    w.setBlock(x, 156, z, 1, 0, 2);
                    boolean rim = Math.abs(x - cx) == 8 || Math.abs(z - cz) == 8;
                    int f = floor[Math.floorMod((x * 7 + z * 3) / 3, floor.length)];
                    w.setBlock(x, 157, z, rim ? 1 : f, 0, 2);
                    for (int y = 158; y <= 161; y++) {
                        w.setBlock(x, y, z, rim ? 1 : 9, 0, 2);
                    }
                }
            }
            // a step inside the pool (shallow part), and a glass pillar sticking out of the water
            for (int x = cx + 2; x <= cx + 6; x++) {
                for (int z = cz - 6; z <= cz - 2; z++) {
                    w.setBlock(x, 158, z, 12, 0, 2);
                    w.setBlock(x, 159, z, 12, 0, 2);
                }
            }
            for (int y = 157; y <= 163; y++) {
                w.setBlock(cx - 3, y, cz + 3, 20, 0, 2);
            }
            // ---- bucket water on flat stone: one source in the open, one against a wall, one on a 1-block pedestal
            for (int x = bx - 12; x <= bx + 12; x++) {
                for (int z = bz - 12; z <= bz + 12; z++) {
                    w.setBlock(x, 150, z, 1, 0, 2);
                    for (int y = 151; y <= 156; y++) {
                        w.setBlock(x, y, z, 0, 0, 2);
                    }
                }
            }
            for (int z = bz - 12; z <= bz + 12; z++) {
                w.setBlock(bx + 6, 151, z, 1, 0, 2);
                w.setBlock(bx + 6, 152, z, 1, 0, 2);
            }
            w.setBlock(bx - 4, 151, bz - 4, 9, 0, 3);
            w.setBlock(bx + 5, 151, bz + 2, 9, 0, 3);
            w.setBlock(bx - 3, 151, bz + 6, 1, 0, 2);
            w.setBlock(bx - 3, 152, bz + 6, 9, 0, 3);
        });

        // ---- natural water: river (shallow, shore) and ocean, deterministic search
        int[] river = surface(956, 1500);
        int[] ocean = surface(-456, 1500);
        Bench.log("water: river " + Arrays.toString(river) + ", ocean " + Arrays.toString(ocean));

        sectionTest(ox, oz);
        if (Boolean.getBoolean("fml.vulkan.waterScene.sectionsOnly")) {
            Bench.log("water: done");
            return;
        }
        List<Shot> shots = new ArrayList<>();
        shots.add(new Shot("pool_above", cx - 14.5, 166, cz - 14.5, -45, 30));
        shots.add(new Shot("pool_low", cx + 0.5, 161.5, cz - 13.5, 0, 12));
        shots.add(new Shot("pool_topdown", cx + 0.5, 178, cz + 0.5, 0, 90));
        shots.add(new Shot("pool_under", cx - 1.5, 157.2, cz - 5.5, 10, 8));
        shots.add(new Shot("pool_under_up", cx + 0.5, 156.5, cz + 0.5, 0, -60));
        shots.add(new Shot("bucket_above", bx - 10.5, 156, bz - 10.5, -45, 32));
        shots.add(new Shot("bucket_close", bx - 4.5, 153, bz - 8.5, 0, 35));
        shots.add(new Shot("bucket_side", bx - 0.5, 151.2, bz + 10.5, 180, 8));
        if (river != null) {
            shots.add(new Shot("river_above", river[0] + 0.5, river[1] + 14, river[2] - 16.5, 0, 35));
            shots.add(new Shot("river_low", river[0] + 0.5, river[1] + 2.5, river[2] - 10.5, 0, 15));
            shots.add(new Shot("river_under", river[0] + 0.5, river[1] - 2.2, river[2] + 0.5, 0, 10));
        }
        if (ocean != null) {
            shots.add(new Shot("ocean_above", ocean[0] + 0.5, ocean[1] + 20, ocean[2] + 0.5, -90, 30));
            shots.add(new Shot("ocean_under", ocean[0] + 0.5, ocean[1] - 6, ocean[2] + 0.5, -90, 10));
        }
        for (Shot s : shots) {
            capture(s);
        }
        // change test (resort / rebuild): put a block into the pool, remove the bucket sources, capture again
        bench.server(() -> {
            WorldServer w = bench.serverWorld(0);
            for (int y = 157; y <= 163; y++) {
                w.setBlock(cx - 3, y, cz + 3, 9, 0, 3);
            }
            w.setBlock(cx + 4, 160, cz + 4, 1, 0, 3);
            w.setBlock(bx - 4, 151, bz - 4, 0, 0, 3);
            w.setBlock(bx + 5, 151, bz + 2, 1, 0, 3);
        });
        bench.waitTicks(200);
        capture(new Shot("edit_pool_above", cx - 14.5, 166, cz - 14.5, -45, 30));
        capture(new Shot("edit_bucket_above", bx - 10.5, 156, bz - 10.5, -45, 32));
        Bench.log("water: done");
    }

    private static String sectionDebug(int x, int y, int z) {
        try {
            return String.valueOf(Class.forName("net.xiaoyu233.fml.vulkan.render.terrain.TerrainRenderer").getMethod("sectionDebug", int.class, int.class, int.class)
                .invoke(null, x, y, z));
        } catch (ReflectiveOperationException | LinkageError e) {
            return "n/a";
        }
    }

    /** Places glass / stone in many sections around the player (near, far, high air sections, after a move) and reports the section state of each. */
    private void sectionTest(int ox, int oz) throws Exception {
        int[] ys = {bench.client(() -> bench.mc.theWorld.getHeightValue(ox, oz)) + 1, 120, 200};
        List<int[]> pts = new ArrayList<>();
        for (int y : ys) {
            for (int dx = -96; dx <= 96; dx += 24) {
                for (int dz = -96; dz <= 96; dz += 24) {
                    pts.add(new int[] {ox + dx + 3, y, oz + dz + 5});
                }
            }
        }
        for (int round = 0; round < 2; round++) {
            double px = ox + 0.5 + round * 40, pz = oz + 0.5 + round * 40;
            bench.server(() -> bench.serverPlayer().playerNetServerHandler.setPlayerLocation(px, 150, pz, 0, 30));
            bench.startPath(BenchRoute.idle(px, 150, pz, 0, 30, 1_000_000));
            bench.waitTicks(100);
            int id = round == 0 ? 20 : 1;
            for (int[] p : pts) {
                bench.server(() -> bench.serverWorld(0).setBlock(p[0], p[1], p[2], id, 0, 3));
                bench.waitTicks(1);
            }
            bench.waitTicks(200);
            int bad = 0;
            for (int[] p : pts) {
                String st = bench.client(() -> {
                    int cid = bench.mc.theWorld.getBlockId(p[0], p[1], p[2]);
                    return "client id " + cid + " " + sectionDebug(p[0], p[1], p[2]);
                });
                boolean ok = st.contains("state=4") && st.matches(".* v=(\\d+) built=\\1 .*");
                if (!ok) {
                    bad++;
                }
                Bench.log("water: section round " + round + " " + p[0] + "/" + p[1] + "/" + p[2] + (ok ? " OK " : " STUCK ") + st);
            }
            Bench.log("water: section round " + round + ": " + bad + " of " + pts.size() + " not rebuilt");
            bench.stopPath();
        }
    }

    private int[] surface(int x, int z) throws Exception {
        return bench.server(() -> {
            WorldServer w = bench.serverWorld(0);
            w.theChunkProviderServer.loadChunk(x >> 4, z >> 4);
            int top = 62;
            for (int y = 120; y > 0; y--) {
                if (w.getBlockId(x, y, z) != 0) {
                    top = y;
                    break;
                }
            }
            return new int[] {x, top, z};
        });
    }

    private int[] biome(int ox, int oz, BiomeGenBase b) throws Exception {
        return bench.server(() -> {
            WorldServer w = bench.serverWorld(0);
            ChunkPosition cp = w.getWorldChunkManager().findBiomePosition(ox, oz, 1200, Arrays.asList(b), new Random(Bench.SEED));
            if (cp == null) {
                return null;
            }
            w.theChunkProviderServer.loadChunk(cp.x >> 4, cp.z >> 4);
            int top = 62;
            for (int y = 120; y > 0; y--) {
                if (w.getBlockId(cp.x, y, cp.z) != 0) {
                    top = y;
                    break;
                }
            }
            return new int[] {cp.x, top, cp.z};
        });
    }

    private void preparePlayer() {
        ServerPlayer p = bench.serverPlayer();
        p.setGameType(EnumGameType.CREATIVE);
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

    private void noon() throws Exception {
        bench.server(() -> {
            for (WorldServer w : MinecraftServer.getServer().worldServers) {
                if (w != null && w.provider.dimensionId == 0) {
                    long base = w.getTotalWorldTimeAtStartOfToday();
                    w.setTotalWorldTime(base + 12000L, true);
                }
            }
        });
    }

    private void capture(Shot s) throws Exception {
        bench.server(() -> {
            ServerPlayer p = bench.serverPlayer();
            p.fallDistance = 0.0F;
            p.playerNetServerHandler.setPlayerLocation(s.x, s.y, s.z, s.yaw, s.pitch);
        });
        bench.startPath(BenchRoute.idle(s.x, s.y, s.z, s.yaw, s.pitch, 1_000_000));
        noon();
        // settle: an empty rebuild queue for 40 consecutive checks, then 2 s more
        long end = System.currentTimeMillis() + 60_000;
        int quiet = 0;
        while (quiet < 40 && System.currentTimeMillis() < end) {
            Thread.sleep(25);
            quiet = bench.pendingRebuilds() == 0 ? quiet + 1 : 0;
        }
        bench.waitTicks(40);
        noon();
        bench.waitTicks(10);
        File dir = new File(bench.mc.mcDataDir, "screenshots");
        dir.mkdirs();
        Set<String> before = new HashSet<>(Arrays.asList(dir.list()));
        bench.client(() -> bench.mc.take_screenshot_next_tick = true);
        File got = null;
        long until = System.currentTimeMillis() + 15_000;
        while (got == null && System.currentTimeMillis() < until) {
            Thread.sleep(100);
            for (String n : dir.list()) {
                if (!before.contains(n) && n.endsWith(".png")) {
                    got = new File(dir, n);
                }
            }
        }
        if (got == null) {
            Bench.log("water: screenshot " + s.name + " missing");
        } else {
            Thread.sleep(500);
            Files.move(got.toPath(), new File(out, s.name + ".png").toPath(), StandardCopyOption.REPLACE_EXISTING);
            String rain = bench.client(() -> String.valueOf(bench.mc.theWorld.getRainStrength(1.0F)));
            Bench.log("water: shot " + s.name + " at " + s.x + "/" + s.y + "/" + s.z + " rain " + rain);
        }
        bench.stopPath();
    }
}
