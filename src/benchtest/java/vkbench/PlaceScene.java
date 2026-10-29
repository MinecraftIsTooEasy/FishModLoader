package vkbench;

import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.EnumGameType;
import net.minecraft.ItemStack;
import net.minecraft.Minecraft;
import net.minecraft.ServerPlayer;
import net.minecraft.WorldServer;
import net.minecraft.server.MinecraftServer;

/**
 * Dev only: right-click placement probe (-Dfml.vulkan.placeScene=<dir>). Survival player on a stone platform clicks an iron water bucket
 * (empty it), then the empty bucket (fill), then a stone block; per rendered frame the swing / equip state and the visible block are logged
 * to placement.csv; in slow motion (timer speed) every 2nd frame is also captured as a screenshot. Same code on the OpenGL baseline and native.
 */
final class PlaceScene {
    private static volatile PlaceScene active;
    private final Bench bench;
    private final File out;
    private volatile PrintWriter csv;
    private volatile boolean shots;
    private volatile String phase = "";
    private volatile int tx, ty, tz;
    private long frame;
    private long phaseFrame;
    private final boolean fastShots = Boolean.getBoolean("fml.vulkan.placeFast");
    private int lastBlk, fastLeft, fastDone;
    private long lastQ = Long.MIN_VALUE;
    private final List<String> shotNames = new ArrayList<>();
    private Field equipped, prevEquipped, itemToRender;
    private Method clickMouse;

    PlaceScene(Bench bench, File out) {
        this.bench = bench;
        this.out = out;
    }

    static void onFrame(Minecraft mc) {
        PlaceScene s = active;
        if (s != null) {
            s.frameImpl(mc);
        }
    }

    private void frameImpl(Minecraft mc) {
        PrintWriter w = csv;
        if (w == null || mc.thePlayer == null) {
            return;
        }
        frame++;
        phaseFrame++;
        try {
            Object ir = mc.entityRenderer.itemRenderer;
            float eq = equipped.getFloat(ir), peq = prevEquipped.getFloat(ir);
            ItemStack in = (ItemStack) itemToRender.get(ir);
            ItemStack held = mc.thePlayer.inventory.getCurrentItemStack();
            int blk = mc.theWorld.getBlockId(tx, ty, tz);
            float pt = partialTicks(mc);
            mc.ingameGUI.getChatGUI().clearChatMessages();
            w.println(phase + "," + phaseFrame + "," + System.nanoTime() / 1000 + "," + bench.tickCount() + "," + pt + ","
                    + mc.thePlayer.swingProgress + "," + mc.thePlayer.prevSwingProgress + "," + mc.thePlayer.getSwingProgress(pt) + ","
                    + peq + "," + eq + "," + (peq + (eq - peq) * pt) + "," + (in == null ? -1 : in.itemID) + "," + (held == null ? -1 : held.itemID) + ","
                    + (held == null ? 0 : held.stackSize) + "," + blk + "," + Bench.nativeCommits() + "," + bench.pendingRebuilds());
            if (fastShots && phase.equals("N1_water_empty")) {
                if (lastBlk != blk && fastLeft == 0 && fastDone == 0) {
                    fastLeft = 14;
                }
                lastBlk = blk;
                if (fastLeft > 0) {
                    fastLeft--;
                    fastDone++;
                    mc.take_screenshot_next_tick = true;
                    synchronized (shotNames) {
                        shotNames.add("F_" + String.format("%02d", fastDone) + "_pf" + phaseFrame + "_blk" + blk);
                    }
                }
            }
            long q = (long) Math.floor((bench.tickCount() + pt) * 2.0);
            if (shots && q != lastQ && shotNames.size() < 28 && phase.contains("water_empty")) {
                lastQ = q;
                mc.take_screenshot_next_tick = true;
                synchronized (shotNames) {
                    shotNames.add(phase + "_" + String.format("%04d", phaseFrame));
                }
            }
        } catch (Throwable t) {
            Bench.log("place: frame hook " + t);
        }
    }

    private Object timer(Minecraft mc) throws Exception {
        Field f = Minecraft.class.getDeclaredField("timer");
        f.setAccessible(true);
        return f.get(mc);
    }

    private float partialTicks(Minecraft mc) throws Exception {
        Object t = timer(mc);
        return t.getClass().getField("renderPartialTicks").getFloat(t);
    }

    private void setSpeed(Minecraft mc, float v) {
        try {
            Object t = timer(mc);
            t.getClass().getField("timerSpeed").setFloat(t, v);
        } catch (Exception e) {
            Bench.log("place: setSpeed " + e);
        }
    }

    void run() throws Exception {
        out.mkdirs();
        if (!bench.waitForCondition(bench::mainMenuUp, 6000)) {
            Bench.log("place: main menu not reached");
            return;
        }
        bench.client(() -> {
            bench.mc.gameSettings.pauseOnLostFocus = false;
            bench.mc.gameSettings.showDebugInfo = false;
            bench.mc.gameSettings.viewBobbing = false;
            bench.mc.gameSettings.clouds = false;
        });
        Class<?> irc = Class.forName("net.minecraft.ItemRenderer");
        equipped = irc.getDeclaredField("equippedProgress");
        prevEquipped = irc.getDeclaredField("prevEquippedProgress");
        itemToRender = irc.getDeclaredField("itemToRender");
        equipped.setAccessible(true);
        prevEquipped.setAccessible(true);
        itemToRender.setAccessible(true);
        clickMouse = Minecraft.class.getDeclaredMethod("clickMouse", int.class);
        clickMouse.setAccessible(true);
        bench.createWorld(false, "vkplace");
        if (!bench.waitForCondition(() -> bench.mc.theWorld != null && bench.mc.thePlayer != null && bench.mc.currentScreen == null, 6000)) {
            Bench.log("place: world not entered");
            return;
        }
        bench.waitTicks(40);
        final int cx = 112, cz = 336;
        int[] top = {0};
        bench.server(() -> {
            ServerPlayer p = bench.serverPlayer();
            p.setGameType(EnumGameType.SURVIVAL);
            for (WorldServer w : MinecraftServer.getServer().worldServers) {
                if (w != null) {
                    try {
                        w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
                        w.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
                    } catch (Throwable ignored) {
                    }
                }
            }
            WorldServer w = bench.serverWorld(0);
            top[0] = 200;
            for (int x = cx - 12; x <= cx + 12; x++) {
                for (int z = cz - 12; z <= cz + 12; z++) {
                    for (int y = 195; y <= 215; y++) {
                        w.setBlock(x, y, z, y <= 200 ? 1 : 0, 0, 2);
                    }
                }
            }
            p.playerNetServerHandler.setPlayerLocation(cx + 0.5, 201.0, cz + 0.5, 0, 50);
        });
        bench.waitTicks(100);
        // target: stone block 2 in front (south, +z), top face y=200 -> water goes to y=201
        tx = cx;
        ty = 201;
        tz = cz + 1;
        bench.startPath(BenchRoute.idle(cx + 0.5, 201.0, cz + 0.5, 0, 50, 1_000_000));
        bench.waitTicks(40);
        Bench.log("place: client block at target " + bench.client(() -> bench.mc.theWorld.getBlockId(tx, ty, tz)));
        csv = new PrintWriter(new File(out, "placement.csv"));
        csv.println("phase,pframe,t_us,tick,partial,swing,prevSwing,swingInterp,prevEquip,equip,equipInterp,itemToRender,held,heldCount,blockAtTarget,commits,pending");
        active = this;
        boolean slow = Boolean.getBoolean("fml.vulkan.placeShots") && !fastShots;
        for (int pass = 0; pass < (slow ? 2 : 1); pass++) {
            shots = pass == 1;
            if (shots) {
                bench.client(() -> setSpeed(bench.mc, 0.1F));
            }
            String sfx = shots ? "S" : "N";
            runClick(sfx + "1_water_empty", net.minecraft.Item.bucketIronWater.itemID, 1, cx, cz);
            if (!shots) runClick(sfx + "2_bucket_fill", net.minecraft.Item.bucketIronEmpty.itemID, 1, cx, cz);
            if (!shots) runClick(sfx + "3_stone", net.minecraft.Block.stone.blockID, 4, cx, cz);
            if (shots) {
                bench.client(() -> setSpeed(bench.mc, 1.0F));
            }
        }
        shots = false;
        active = null;
        csv.close();
        csv = null;
        collectShots();
        Bench.log("place: done");
    }

    private void runClick(String name, int itemId, int count, int cx, int cz) throws Exception {
        // reset: clear the target column, give the item (server), wait until equipped
        bench.server(() -> {
            WorldServer w = bench.serverWorld(0);
            w.setBlock(tx, ty, tz, 0, 0, 3);
            w.setBlock(tx, ty + 1, tz, 0, 0, 3);
            ServerPlayer p = bench.serverPlayer();
            p.inventory.mainInventory[0] = new ItemStack(itemId, count, 0);
            p.inventory.currentItem = 0;
            p.inventoryContainer.detectAndSendChanges();
        });
        bench.client(() -> bench.mc.thePlayer.inventory.currentItem = 0);
        bench.waitTicks(60);
        // for the fill case the source must exist: water block next to the player target
        if (name.contains("fill")) {
            bench.server(() -> bench.serverWorld(0).setBlock(tx, ty, tz, 9, 0, 3));
            bench.waitTicks(20);
        }
        phase = name;
        phaseFrame = 0;
        System.out.println("[VkBench] CLICK " + System.nanoTime() / 1000 + " " + name);
        long tick0 = bench.tickCount();
        bench.client(() -> {
            try {
                bench.mc.right_click_counter = 0;
                Bench.log("place: " + name + " objectMouseOver " + bench.mc.objectMouseOver);
                clickMouse.invoke(bench.mc, 1);
            } catch (Throwable t) {
                Bench.log("place: click failed " + t);
            }
        });
        Bench.log("place: " + name + " clicked at tick " + tick0);
        bench.waitTicks(shots ? 14 : 30);
        Bench.log("place: " + name + " end: block " + bench.client(() -> bench.mc.theWorld.getBlockId(tx, ty, tz))
                + " held " + bench.client(() -> String.valueOf(bench.mc.thePlayer.inventory.getCurrentItemStack())));
        phase = "";
    }

    private void collectShots() throws Exception {
        File dir = new File(bench.mc.mcDataDir, "screenshots");
        String[] names = dir.list();
        if (names == null) {
            return;
        }
        final File dd = dir;
        Arrays.sort(names, java.util.Comparator.comparingLong((String n) -> new File(dd, n).lastModified()).thenComparing(n -> n.length()).thenComparing(n -> n));
        List<String> pngs = new ArrayList<>();
        for (String n : names) {
            if (n.endsWith(".png")) {
                pngs.add(n);
            }
        }
        Bench.log("place: screenshots " + pngs.size() + " frames requested " + shotNames.size());
        File sd = new File(out, "frames");
        sd.mkdirs();
        for (int i = 0; i < pngs.size() && i < shotNames.size(); i++) {
            Files.move(new File(dir, pngs.get(i)).toPath(), new File(sd, shotNames.get(i) + ".png").toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
