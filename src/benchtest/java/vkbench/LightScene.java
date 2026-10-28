package vkbench;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.Entity;
import net.minecraft.EntityItem;
import net.minecraft.EntityList;
import net.minecraft.EnumGameType;
import net.minecraft.GuiInventory;
import net.minecraft.ItemStack;
import net.minecraft.ServerPlayer;
import net.minecraft.WorldServer;
import net.minecraft.server.MinecraftServer;

/**
 * Dev only: deterministic entity / hand / terrain lighting scene. Three platforms high above the terrain: a sealed dark room (sky light 0),
 * the same room with torches, and an open surface platform. Mobs stand in glass fronted cells, chests, a sign and dropped items on the north
 * side; the camera looks along each of the four yaws holding an iron sword. Active with {@code -Dfml.vulkan.lightScene=<output dir>}; runs
 * unchanged on the OpenGL baseline and the native build.
 */
final class LightScene {
    private final Bench bench;
    private final File out;
    private volatile long timeOfDay = 6000L;

    LightScene(Bench bench, File out) {
        this.bench = bench;
        this.out = out;
    }

    private record Shot(String name, double x, double y, double z, float yaw, float pitch) {
    }

    void run() throws Exception {
        out.mkdirs();
        if (!bench.waitForCondition(bench::mainMenuUp, 6000)) {
            Bench.log("light: main menu not reached");
            return;
        }
        bench.client(() -> {
            bench.mc.gameSettings.pauseOnLostFocus = false;
            bench.mc.gameSettings.showDebugInfo = false;
            bench.mc.gameSettings.viewBobbing = false;
            bench.mc.gameSettings.clouds = false;
            bench.mc.gameSettings.particleSetting = 2;
        });
        bench.createWorld(false, "vklight");
        if (!bench.waitForCondition(() -> bench.mc.theWorld != null && bench.mc.thePlayer != null && bench.mc.currentScreen == null, 6000)) {
            Bench.log("light: world not entered");
            return;
        }
        bench.server(this::preparePlayer);
        bench.waitTicks(40);
        bench.client(() -> {
            bench.mc.thePlayer.noClip = true;
        });
        bench.server(() -> bench.serverPlayer().noClip = true);
        double[] sp = bench.client(() -> new double[] {bench.mc.thePlayer.posX, bench.mc.thePlayer.posZ});
        int ox = (int) Math.floor(sp[0]), oz = (int) Math.floor(sp[1]);
        final int cx = ((ox >> 4) << 4) + 64, cz = ((oz >> 4) << 4) + 64;
        final int y = 110;
        final int[] centers = {cx, cx + 40, cx + 80};
        String[] names = {"dark", "torch", "surface"};
        bench.server(() -> bench.serverPlayer().playerNetServerHandler.setPlayerLocation(cx + 40.5, 110, cz + 0.5, 0, 0));
        bench.startPath(BenchRoute.idle(cx + 40.5, 110, cz + 0.5, 0, 0, 1_000_000));
        bench.waitTicks(200);
        bench.server(() -> {
            ServerPlayer p = bench.serverPlayer();
            p.inventory.mainInventory[0] = new ItemStack(267, 1, 0);
            p.inventory.currentItem = 0;
            p.inventoryContainer.detectAndSendChanges();
            WorldServer w = bench.serverWorld(0);
            for (int x = cx - 16; x <= cx + 96; x += 16) {
                for (int z = cz - 16; z <= cz + 16; z += 16) {
                    w.theChunkProviderServer.loadChunk(x >> 4, z >> 4);
                }
            }
            for (int r = 0; r < 3; r++) {
                int mx = centers[r];
                boolean room = r < 2;
                for (int x = mx - 7; x <= mx + 7; x++) {
                    for (int z = cz - 7; z <= cz + 7; z++) {
                        w.setBlock(x, y - 1, z, 1, 0, 2);
                        for (int yy = y; yy <= y + 4; yy++) {
                            boolean wall = Math.abs(x - mx) == 7 || Math.abs(z - cz) == 7;
                            w.setBlock(x, yy, z, room && (wall || yy == y + 4) ? 1 : 0, 0, 2);
                        }
                    }
                }
                int[][] dirs = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};
                for (int d = 0; d < 4; d++) {
                    int dx = dirs[d][0], dz = dirs[d][1];
                    int px = -dz, pz = dx;
                    if (d == 2) {
                        w.setBlock(mx + dx * 4, y, cz + dz * 4, 54, 0, 3);
                        w.setBlock(mx + dx * 4 + px * 2, y, cz + dz * 4 + pz * 2, 63, 8, 3);
                        w.setBlock(mx + dx * 4 - px * 2, y, cz + dz * 4 - pz * 2, 54, 0, 3);
                        continue;
                    }
                    int bx = mx + dx * 4, bz = cz + dz * 4;
                    for (int yy = y; yy <= y + 2; yy++) {
                        w.setBlock(bx + dx, yy, bz + dz, 1, 0, 2);
                        w.setBlock(bx + px, yy, bz + pz, 1, 0, 2);
                        w.setBlock(bx - px, yy, bz - pz, 1, 0, 2);
                        w.setBlock(bx - dx, yy, bz - dz, 20, 0, 2);
                        w.setBlock(bx, yy, bz, 0, 0, 2);
                    }
                    w.setBlock(bx, y + 3, bz, 1, 0, 2);
                }
                if (r == 1) {
                    w.setBlock(mx + 2, y, cz + 2, 50, 5, 3);
                    w.setBlock(mx - 2, y, cz - 2, 50, 5, 3);
                }
            }
        });
        bench.waitTicks(20);
        String[][] mobs = {{"Zombie", "Creeper", "Zombie"}, {"Zombie", "Creeper", "Zombie"}, {"Cow", "Pig", "Sheep"}};
        for (int r = 0; r < 3; r++) {
            final int rr = r;
            bench.server(() -> {
                WorldServer w = bench.serverWorld(0);
                int mx = centers[rr];
                int[][] cells = {{0, 4}, {-4, 0}, {4, 0}};
                for (int i = 0; i < 3; i++) {
                    Entity e = EntityList.createEntityByName(mobs[rr][i], w);
                    if (e == null) {
                        Bench.log("light: unknown entity " + mobs[rr][i]);
                        continue;
                    }
                    e.setLocationAndAngles(mx + cells[i][0] + 0.5, y, cz + cells[i][1] + 0.5, i * 90.0F, 0.0F);
                    w.spawnEntityInWorld(e);
                }
                EntityItem item = new EntityItem(w, mx + 0.5, y + 0.3, cz - 3.5, new ItemStack(264, 1, 0));
                item.motionX = item.motionY = item.motionZ = 0.0;
                w.spawnEntityInWorld(item);
                EntityItem item2 = new EntityItem(w, mx + 1.5, y + 0.3, cz - 3.5, new ItemStack(1, 1, 0));
                item2.motionX = item2.motionY = item2.motionZ = 0.0;
                w.spawnEntityInWorld(item2);
            });
        }
        bench.waitTicks(200);
        float[] yaws = {0, 90, 180, -90};
        for (int r = 0; r < 3; r++) {
            timeOfDay = 6000L;
            for (float yaw : yaws) {
                capture(new Shot(names[r] + "_yaw" + (int) yaw, centers[r] + 0.5, y, cz + 0.5, yaw, 4));
            }
            if (r == 2) {
                timeOfDay = 18000L;
                for (float yaw : new float[] {0, 180}) {
                    capture(new Shot("surface_night_yaw" + (int) yaw, centers[r] + 0.5, y, cz + 0.5, yaw, 4));
                }
                timeOfDay = 6000L;
            }
        }
        capture(new Shot("dark_down", centers[0] + 0.5, y, cz + 0.5, 30, 60));
        capture(new Shot("torch_down", centers[1] + 0.5, y, cz + 0.5, 30, 60));
        bench.client(() -> bench.mc.displayGuiScreen(new GuiInventory(bench.mc.thePlayer)));
        bench.waitTicks(10);
        capture(new Shot("gui_inventory", centers[0] + 0.5, y, cz + 0.5, 0, 4));
        bench.client(() -> bench.mc.displayGuiScreen(null));
        Bench.log("light: done");
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
                    w.setTotalWorldTime(base + timeOfDay, true);
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
            Bench.log("light: screenshot " + s.name + " missing");
        } else {
            Thread.sleep(500);
            Files.move(got.toPath(), new File(out, s.name + ".png").toPath(), StandardCopyOption.REPLACE_EXISTING);
            String rain = bench.client(() -> String.valueOf(bench.mc.theWorld.getRainStrength(1.0F)));
            Bench.log("light: shot " + s.name + " at " + s.x + "/" + s.y + "/" + s.z + " rain " + rain);
        }
        bench.stopPath();
    }
}
