package net.xiaoyu233.fml.vulkan.testbot.cmd.nr12;

import net.minecraft.Block;
import net.minecraft.Minecraft;
import net.minecraft.PotionEffect;
import net.minecraft.Potion;
import net.xiaoyu233.fml.vulkan.testbot.Frozen;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.Stage;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * {@code worldFrameCapture [fog|underwater|lava|blindness|underworld ...]}: frozen captures of the world frame states of NR-12 (distance fog, camera
 * in water, camera in lava, blindness potion, Underworld dimension) with the {@link Frozen} helpers (pinned counters, fixed partial tick and clock,
 * locked weather); the png of every state lands in the capture directory as {@code nr12_<state>.png} next to the scene manifest. The compare against
 * the fml.vulkan.native=false run (T1) is done by the capture compare of the bot. Every state is undone afterwards.
 */
final class WorldFrameCaptureCommand implements DevCommand {
    private static final String[] ALL = {"fog", "underwater", "lava", "blindness", "underworld"};

    @Override
    public String name() {
        return "worldFrameCapture";
    }

    @Override
    public String description() {
        return "frozen captures of fog / underwater / lava / blindness / Underworld world frames (nr12_<state>.png)";
    }

    @Override
    public void run(Report.Section s, String[] args) throws Exception {
        TestBot bot = TestBot.get();
        if (bot == null || bot.stage() == null || bot.minecraft().theWorld == null) {
            s.fail("worldFrameCapture", "needs a running scripted world (testbot)");
            return;
        }
        if (!Frozen.active()) {
            s.warn("frozen mode", "not in frozen mode (-Pvk.frozen=true): captures are not reproducible");
        }
        String[] states = args.length > 0 ? args : ALL;
        Stage stage = bot.stage();
        Minecraft mc = bot.minecraft();
        double[] c = stage.ensureMuseum();
        for (String st : states) {
            try {
                bot.status("worldFrameCapture " + st);
                stage.prepPlayer();
                stage.place(c[0], c[1], c[2] - 8, 0, 8);
                stage.setTimeOfDay(12000);
                bot.client(() -> Frozen.lockWeather(mc.theWorld, 0.0F));
                switch (st) {
                    case "fog" -> {
                        // thick fog: the vanilla render distance fog is always on, a far look at the horizon shows it; rain strength makes it denser
                        stage.look(0, 0);
                        bot.client(() -> Frozen.lockWeather(mc.theWorld, 1.0F));
                        capture(s, bot, st);
                    }
                    case "underwater" -> inFluid(s, bot, st, Block.waterStill.blockID, c);
                    case "lava" -> inFluid(s, bot, st, Block.lavaStill.blockID, c);
                    case "blindness" -> {
                        bot.client(() -> mc.thePlayer.addPotionEffect(new PotionEffect(Potion.blindness.id, 2000, 0)));
                        bot.frames(5);
                        try {
                            capture(s, bot, st);
                        } finally {
                            bot.client(() -> mc.thePlayer.removePotionEffect(Potion.blindness.id));
                        }
                    }
                    case "underworld" -> underworld(s, bot, st);
                    default -> s.fail("worldFrameCapture", "unknown state '" + st + "' (fog, underwater, lava, blindness, underworld)");
                }
            } catch (Throwable t) {
                s.exception("worldFrameCapture " + st, t);
            } finally {
                bot.client(() -> {
                    Frozen.endCapture();
                    Frozen.unlockWeather();
                });
            }
        }
        stage.prepPlayer();
    }

    /** Fills a 3x3x3 cube around the camera with the fluid (client and server), captures, then restores air. */
    private static void inFluid(Report.Section s, TestBot bot, String state, int blockId, double[] c) throws Exception {
        net.minecraft.Minecraft mc = bot.minecraft();
        int bx = (int) Math.floor(c[0]), by = (int) Math.floor(c[1]) + 1, bz = (int) Math.floor(c[2]) - 8;
        bot.server(() -> {
            net.minecraft.WorldServer w = net.minecraft.server.MinecraftServer.getServer().worldServerForDimension(mc.theWorld.getDimensionId());
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 3; y++) {
                    for (int z = -1; z <= 1; z++) {
                        w.setBlock(bx + x, by + y, bz + z, blockId);
                    }
                }
            }
            return null;
        });
        try {
            bot.ticks(5);
            stage(bot).quietFrames(10, 300);
            capture(s, bot, state);
        } finally {
            bot.server(() -> {
                net.minecraft.WorldServer w = net.minecraft.server.MinecraftServer.getServer().worldServerForDimension(mc.theWorld.getDimensionId());
                for (int x = -1; x <= 1; x++) {
                    for (int y = -1; y <= 3; y++) {
                        for (int z = -1; z <= 1; z++) {
                            w.setBlock(bx + x, by + y, bz + z, 0);
                        }
                    }
                }
                return null;
            });
        }
    }

    private static Stage stage(TestBot bot) {
        return bot.stage();
    }

    private static void underworld(Report.Section s, TestBot bot, String state) throws Exception {
        net.minecraft.Minecraft mc = bot.minecraft();
        bot.server(() -> {
            net.minecraft.ServerPlayer p = (net.minecraft.ServerPlayer) net.minecraft.server.MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
            net.minecraft.server.MinecraftServer.getServer().getConfigurationManager().transferPlayerToDimension(p, -2);
            return null;
        });
        boolean there = bot.waitFor(() -> mc.theWorld != null && mc.theWorld.getDimensionId() == -2, 400);
        s.check(there, "Underworld reached", "client did not arrive in dimension -2");
        try {
            if (there) {
                bot.ticks(40);
                stage(bot).quietFrames(20, 1500);
                capture(s, bot, state);
            }
        } finally {
            bot.server(() -> {
                net.minecraft.ServerPlayer p = (net.minecraft.ServerPlayer) net.minecraft.server.MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
                net.minecraft.server.MinecraftServer.getServer().getConfigurationManager().transferPlayerToDimension(p, 0);
                return null;
            });
            bot.waitFor(() -> mc.theWorld != null && mc.theWorld.getDimensionId() == 0, 400);
            bot.ticks(20);
        }
    }

    private static void capture(Report.Section s, TestBot bot, String state) throws Exception {
        Minecraft mc = bot.minecraft();
        bot.stage().quietFrames(10, 600);
        bot.client(() -> {
            if (mc.ingameGUI != null) {
                mc.ingameGUI.getChatGUI().clearChatMessages();
            }
            Frozen.pinClientCounters(mc);
            Frozen.beginCapture(Frozen.DEFAULT_PARTIAL_TICKS);
        });
        bot.frames(3);
        bot.client(() -> Frozen.pinClientCounters(mc));
        bot.frames(3);
        String state1 = bot.client(() -> Frozen.fingerprint(mc));
        String path = bot.screenshot(s, "nr12_" + state);
        s.info("state nr12_" + state, state1);
        bot.client(Frozen::endCapture);
        s.check(path != null, "world frame '" + state + "' captured as nr12_" + state + ".png (T1)", "no capture file");
    }
}
