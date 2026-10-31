package vkbench;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.EnumGameType;
import net.minecraft.ItemStack;
import net.minecraft.ServerPlayer;
import net.minecraft.WorldServer;
import net.minecraft.server.MinecraftServer;

/**
 * Dev only: first-person hand captures above / half in / under water and in lava (empty hand, sword, block). Active with
 * {@code -Dfml.vulkan.handScene=<output dir>}; runs unchanged on the OpenGL baseline and the native build.
 */
final class HandScene {
    private final Bench bench;
    private final File out;

    HandScene(Bench bench, File out) {
        this.bench = bench;
        this.out = out;
    }

    private record Shot(String name, double x, double eyeY, double z, float yaw, float pitch) {
    }

    void run() throws Exception {
        out.mkdirs();
        if (!bench.waitForCondition(bench::mainMenuUp, 6000)) {
            Bench.log("hand: main menu not reached");
            return;
        }
        bench.client(() -> {
            bench.mc.gameSettings.pauseOnLostFocus = false;
            bench.mc.gameSettings.showDebugInfo = false;
            bench.mc.gameSettings.viewBobbing = Boolean.getBoolean("fml.vulkan.handScene.bob");
            bench.mc.gameSettings.clouds = Boolean.getBoolean("fml.vulkan.handScene.parts");
            bench.mc.gameSettings.particleSetting = Boolean.getBoolean("fml.vulkan.handScene.parts") ? 0 : 2;
        });
        if (Boolean.getBoolean("fml.vulkan.handScene.fast")) {
            bench.client(() -> bench.mc.gameSettings.setOptionValue(net.minecraft.EnumOptions.GRAPHICS, 1));
        }
        bench.createWorld(false, "vkhand");
        if (!bench.waitForCondition(() -> bench.mc.theWorld != null && bench.mc.thePlayer != null && bench.mc.currentScreen == null, 6000)) {
            Bench.log("hand: world not entered");
            return;
        }
        bench.server(this::preparePlayer);
        bench.waitTicks(40);
        if (!Boolean.getBoolean("fml.vulkan.handScene.parts")) {
            bench.client(() -> bench.mc.thePlayer.noClip = true);
            bench.server(() -> bench.serverPlayer().noClip = true);
        }
        final int cx = 112, cz = 336;
        final int lz = cz + 30;
        bench.server(() -> {
            WorldServer w = bench.serverWorld(0);
            // water pool 17x17, water 158..161 (deep 4), a shallow step (water 160..161) at cx+2..cx+6, cz-6..cz-2
            for (int x = cx - 8; x <= cx + 8; x++) {
                for (int z = cz - 8; z <= cz + 8; z++) {
                    boolean rim = Math.abs(x - cx) == 8 || Math.abs(z - cz) == 8;
                    w.setBlock(x, 156, z, 1, 0, 2);
                    w.setBlock(x, 157, z, 1, 0, 2);
                    for (int y = 158; y <= 161; y++) {
                        w.setBlock(x, y, z, rim ? 1 : 9, 0, 2);
                    }
                    for (int y = 162; y <= 175; y++) {
                        w.setBlock(x, y, z, 0, 0, 2);
                    }
                }
            }
            for (int x = cx + 2; x <= cx + 6; x++) {
                for (int z = cz - 6; z <= cz - 2; z++) {
                    w.setBlock(x, 158, z, 12, 0, 2);
                    w.setBlock(x, 159, z, 12, 0, 2);
                }
            }
            // lava pool 9x9, lava 158..161
            for (int x = cx - 4; x <= cx + 4; x++) {
                for (int z = lz - 4; z <= lz + 4; z++) {
                    boolean rim = Math.abs(x - cx) == 4 || Math.abs(z - lz) == 4;
                    w.setBlock(x, 156, z, 1, 0, 2);
                    w.setBlock(x, 157, z, 1, 0, 2);
                    for (int y = 158; y <= 161; y++) {
                        w.setBlock(x, y, z, rim ? 1 : 11, 0, 2);
                    }
                    for (int y = 162; y <= 175; y++) {
                        w.setBlock(x, y, z, 0, 0, 2);
                    }
                }
            }
        });
        bench.server(() -> bench.serverPlayer().playerNetServerHandler.setPlayerLocation(cx + 0.5, 166, cz - 14.5, 0, 20));
        bench.waitTicks(100);
        String[] items = {"empty", "sword", "block"};
        for (int slot = 0; slot < 3; slot++) {
            final int s = slot;
            bench.server(() -> {
                ServerPlayer p = bench.serverPlayer();
                p.inventory.mainInventory[0] = null;
                p.inventory.mainInventory[1] = new ItemStack(267, 1, 0);
                p.inventory.mainInventory[2] = new ItemStack(1, 1, 0);
                p.inventory.currentItem = s;
                p.inventoryContainer.detectAndSendChanges();
            });
            bench.client(() -> bench.mc.thePlayer.inventory.currentItem = s);
            bench.waitTicks(40);
            String n = items[slot];
            // eye heights; the water surface of a source block is at y 161.889
            capture(new Shot(n + "_1above", cx - 4.5, 164.0, cz - 4.5, 0, 25));
            capture(new Shot(n + "_2half_lo", cx - 4.5, 161.80, cz - 4.5, 0, 25));
            capture(new Shot(n + "_3half_hi", cx - 4.5, 161.95, cz - 4.5, 0, 25));
            capture(new Shot(n + "_4shallow", cx + 4.5, 160.7, cz - 4.5, 0, 25));
            capture(new Shot(n + "_5deep", cx - 4.5, 159.6, cz + 4.5, 0, 25));
            capture(new Shot(n + "_6deep_level", cx - 4.5, 159.6, cz + 4.5, 90, 0));
            capture(new Shot(n + "_7lava", cx + 0.5, 160.0, lz + 0.5, 0, 25));
        }
        if (Boolean.getBoolean("fml.vulkan.handScene.ocean")) {
            int[] oc = bench.server(() -> {
                WorldServer w = bench.serverWorld(0);
                w.theChunkProviderServer.loadChunk(-456 >> 4, 1500 >> 4);
                int top = 62;
                for (int y = 120; y > 0; y--) {
                    if (w.getBlockId(-456, y, 1500) != 0) {
                        top = y;
                        break;
                    }
                }
                return new int[] {-456, top, 1500};
            });
            Bench.log("hand: ocean " + oc[0] + "/" + oc[1] + "/" + oc[2]);
            for (int slot = 0; slot < 3; slot++) {
                final int s2 = slot;
                bench.server(() -> {
                    ServerPlayer p = bench.serverPlayer();
                    p.inventory.mainInventory[0] = null;
                    p.inventory.mainInventory[1] = new ItemStack(267, 1, 0);
                    p.inventory.mainInventory[2] = new ItemStack(1, 1, 0);
                    p.inventory.currentItem = s2;
                    p.inventoryContainer.detectAndSendChanges();
                });
                bench.client(() -> bench.mc.thePlayer.inventory.currentItem = s2);
                bench.waitTicks(40);
                String n = "ocean_" + items[slot];
                capture(new Shot(n + "_1above", oc[0] + 0.5, oc[1] + 3.0, oc[2] + 0.5, 0, 25));
                capture(new Shot(n + "_2surface", oc[0] + 0.5, oc[1] + 0.85, oc[2] + 0.5, 0, 25));
                capture(new Shot(n + "_3under", oc[0] + 0.5, oc[1] - 1.5, oc[2] + 0.5, 0, 25));
                capture(new Shot(n + "_4deep", oc[0] + 0.5, oc[1] - 6, oc[2] + 0.5, 90, 10));
            }
        }
        Bench.log("hand: done");
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
            p.playerNetServerHandler.setPlayerLocation(s.x, s.eyeY - 1.62, s.z, s.yaw, s.pitch);
        });
        bench.startPath(BenchRoute.idle(s.x, s.eyeY - 1.62, s.z, s.yaw, s.pitch, 1_000_000));
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
            Bench.log("hand: screenshot " + s.name + " missing");
        } else {
            Thread.sleep(500);
            Files.move(got.toPath(), new File(out, s.name + ".png").toPath(), StandardCopyOption.REPLACE_EXISTING);
            String rain = bench.client(() -> String.valueOf(bench.mc.theWorld.getRainStrength(1.0F)));
            Bench.log("hand: posY " + bench.client(() -> bench.mc.thePlayer.posY + "/eye " + bench.mc.thePlayer.getEyePos().yCoord) + " shot " + s.name + " at " + s.x + "/" + s.eyeY + "/" + s.z + " rain " + rain);
        }
        bench.stopPath();
    }
}
