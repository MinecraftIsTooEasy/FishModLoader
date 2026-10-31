package vkbench;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.EnumGameType;
import net.minecraft.ServerPlayer;
import net.minecraft.WorldServer;
import net.minecraft.server.MinecraftServer;

/**
 * Dev only: Twilight Forest sky / water captures near sea level (y 31), looking down at water and up at the sky. Active with
 * {@code -Dfml.vulkan.tfSkyScene=<output dir>}; needs the Twilight Forest mod on the classpath ({@code -Pbench_tf=true}).
 */
final class TfSkyScene {
    private final Bench bench;
    private final File out;

    TfSkyScene(Bench bench, File out) {
        this.bench = bench;
        this.out = out;
    }

    void run() throws Exception {
        out.mkdirs();
        if (!bench.waitForCondition(bench::mainMenuUp, 6000)) {
            Bench.log("tfsky: main menu not reached");
            return;
        }
        bench.client(() -> {
            bench.mc.gameSettings.pauseOnLostFocus = false;
            bench.mc.gameSettings.showDebugInfo = false;
        });
        String saved = System.getProperty("fml.vulkan.tfSkyScene.world");
        bench.createWorld(saved != null, saved != null ? saved : "vktfsky");
        if (!bench.waitForCondition(() -> bench.mc.theWorld != null && bench.mc.thePlayer != null && bench.mc.currentScreen == null, 6000)) {
            Bench.log("tfsky: world not entered");
            return;
        }
        if (saved != null) {
            // a user's world as saved: shots from the player's own position, no edits
            bench.waitTicks(100);
            double[] p = bench.client(() -> new double[] {bench.mc.thePlayer.posX, bench.mc.thePlayer.boundingBox.minY + 1.62, bench.mc.thePlayer.posZ,
                    bench.mc.thePlayer.rotationYaw, bench.mc.theWorld.getDimensionId()});
            Bench.log("tfsky: saved world at " + p[0] + "/" + p[1] + "/" + p[2] + " dim " + (int) p[4]);
            for (int round = 0; round < 2; round++) {
                capture("saved_r" + round + "_down", p[0], p[1], p[2], (float) p[3], 35);
                capture("saved_r" + round + "_level", p[0], p[1], p[2], (float) p[3], 0);
                capture("saved_r" + round + "_up", p[0], p[1], p[2], (float) p[3], -60);
            }
            Bench.log("tfsky: done");
            return;
        }
        int id = (Integer) Class.forName("twilightforest.dimension.TFDimension").getField("id").get(null);
        bench.server(() -> bench.serverPlayer().travelToDimension(id));
        if (!bench.waitForCondition(() -> bench.mc.theWorld != null && bench.mc.thePlayer != null && bench.mc.theWorld.getDimensionId() == id, 1200)) {
            Bench.log("tfsky: did not arrive in TF");
            return;
        }
        bench.waitTicks(20);
        bench.server(() -> {
            ServerPlayer p = bench.serverPlayer();
            p.setGameType(EnumGameType.SURVIVAL);
            p.capabilities.allowFlying = true;
            p.capabilities.isFlying = true;
            p.capabilities.disableDamage = true;
            p.sendPlayerAbilities();
        });
        final int cx = 200, cz = 200;
        bench.server(() -> {
            WorldServer w = bench.serverWorld(id);
            for (int x = cx - 12; x <= cx + 12; x++) {
                for (int z = cz - 12; z <= cz + 12; z++) {
                    w.setBlock(x, 26, z, 1, 0, 2);
                    for (int y = 27; y <= 30; y++) {
                        boolean pool = Math.abs(x - cx) <= 6 && Math.abs(z - cz) <= 6;
                        w.setBlock(x, y, z, pool ? 9 : 1, 0, 2);
                    }
                    for (int y = 31; y <= 120; y++) {
                        w.setBlock(x, y, z, 0, 0, 2);
                    }
                }
            }
            w.setBlock(cx, 30, cz, 1, 0, 2);
        });
        for (int round = 0; round < 2; round++) {
            String r = "r" + round + "_";
            if (round == 1) {
                // user report: opening the inventory once breaks the TF sky/water for the rest of the session
                bench.server(() -> {
                    // TF items with their own item renderers (ice, fiery, giant), like the user's hotbar
                    ServerPlayer p = bench.serverPlayer();
                    String[] names = {"iceSword", "fieryPick", "fierySword", "giantPick", "giantSword", "fieryIngot"};
                    for (int i = 0; i < names.length; i++) {
                        try {
                            net.minecraft.Item item = (net.minecraft.Item) Class.forName("twilightforest.item.TFItems").getField(names[i]).get(null);
                            p.inventory.mainInventory[i] = new net.minecraft.ItemStack(item);
                        } catch (ReflectiveOperationException e) {
                            Bench.log("tfsky: no item " + names[i]);
                        }
                    }
                    p.inventoryContainer.detectAndSendChanges();
                });
                bench.waitTicks(20);
                bench.client(() -> bench.mc.playerController.setGameType(EnumGameType.SURVIVAL));
                bench.waitTicks(5);
                bench.client(() -> bench.mc.displayGuiScreen(new net.minecraft.GuiInventory(bench.mc.thePlayer)));
                bench.waitTicks(60);
                bench.client(() -> bench.mc.displayGuiScreen(null));
                bench.waitTicks(20);
                Bench.log("tfsky: inventory opened and closed");
            }
            capture(r + "1down_y32", cx - 9.5, 33.6, cz + 0.5, -90, 35);
            capture(r + "2level_y32", cx - 9.5, 33.6, cz + 0.5, -90, 0);
            capture(r + "3up_y32", cx - 9.5, 33.6, cz + 0.5, -90, -60);
            capture(r + "4down_y60", cx - 9.5, 60, cz + 0.5, -90, 50);
            capture(r + "5up_y60", cx - 9.5, 60, cz + 0.5, -90, -60);
            capture(r + "6down_y29", cx - 9.5, 29.6, cz + 0.5, -90, 20);
        }
        Bench.log("tfsky: done");
    }

    private void capture(String name, double x, double eyeY, double z, float yaw, float pitch) throws Exception {
        bench.server(() -> {
            ServerPlayer p = bench.serverPlayer();
            p.fallDistance = 0.0F;
            p.playerNetServerHandler.setPlayerLocation(x, eyeY - 1.62, z, yaw, pitch);
        });
        bench.startPath(BenchRoute.idle(x, eyeY - 1.62, z, yaw, pitch, 1_000_000));
        long end = System.currentTimeMillis() + 60_000;
        int quiet = 0;
        while (quiet < 40 && System.currentTimeMillis() < end) {
            Thread.sleep(25);
            quiet = bench.pendingRebuilds() == 0 ? quiet + 1 : 0;
        }
        bench.waitTicks(30);
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
            Bench.log("tfsky: screenshot " + name + " missing");
        } else {
            Thread.sleep(500);
            Files.move(got.toPath(), new File(out, name + ".png").toPath(), StandardCopyOption.REPLACE_EXISTING);
            Bench.log("tfsky: shot " + name);
        }
        bench.stopPath();
    }
}
