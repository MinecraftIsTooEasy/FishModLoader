package net.xiaoyu233.fml.vulkan.testbot.cmd.nr11;

import java.util.Arrays;
import java.util.List;
import net.minecraft.Minecraft;
import net.minecraft.WorldServer;
import net.minecraft.server.MinecraftServer;
import net.xiaoyu233.fml.vulkan.testbot.Frozen;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.Stage;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * Shared helpers of the NR-11 devtest commands (frozen screenshots, MITE clock and moon phase, museum poses) and the command
 * {@code sky.capture}: frozen screenshots of the sky at day, sunset, night and the eight moon phases (MITE clock, weather locked clear).
 *
 * <p>Frozen captures follow {@code CaptureRunner}: quiet renderer, pinned client counters, a forced partial tick, then the presented frame
 * that shows the state. Commands run on the bot script thread and reach the render thread only through {@link TestBot}.
 */
final class SkyCapture {
    /** Render partial tick of the captures (same default as the scripted scenes). */
    static final float PARTIAL = Frozen.DEFAULT_PARTIAL_TICKS;

    /** Adjusted time of the moon phase captures: night on the MITE clock. */
    static final long MOON_TIME = 18000L;

    private SkyCapture() {
    }

    /** Fixed one decimal rendering of a coordinate (same as the scripted scenes). */
    static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** The running bot with a scripted world, or null after failing the section. */
    static TestBot botFor(Report.Section s) {
        TestBot bot = TestBot.get();
        if (bot == null || bot.stage() == null) {
            s.fail("bot", "no running test bot with a scripted world (the command runs inside the 06_commands step)");
            return null;
        }
        return bot;
    }

    /**
     * Frozen screenshot {@code <captureDir>/<name>.png} of the current state. Returns the path or null. Call on the bot script thread.
     */
    static String frozenShot(TestBot bot, Report.Section s, String name) throws Exception {
        Stage stage = bot.stage();
        Minecraft mc = bot.minecraft();
        if (!stage.quietFrames(20, 1500)) {
            s.warn("scene " + name, "renderer did not reach 20 quiet frames within 1500 frames; captured anyway");
        }
        bot.client(() -> {
            Frozen.pinClientCounters(mc);
            Frozen.beginCapture(PARTIAL);
        });
        bot.frames(3);
        // the first frame of the window may still have used the real partial tick: pin the counters again
        bot.client(() -> Frozen.pinClientCounters(mc));
        bot.frames(3);
        String state = bot.client(() -> Frozen.fingerprint(mc));
        String path = bot.screenshot(s, name);
        s.info("state " + name, state);
        bot.client(Frozen::endCapture);
        s.check(path != null, "frozen capture " + name + ".png", "no capture file");
        return path;
    }

    /** The museum pose: the player at the museum centre + (dx, dy, dz) facing yaw/pitch. Returns false when the client did not reach it. */
    static boolean museumPose(TestBot bot, double dx, double dy, double dz, float yaw, float pitch) throws Exception {
        double[] c = bot.stage().ensureMuseum();
        return bot.stage().place(c[0] + dx, c[1] + dy, c[2] + dz, yaw, pitch);
    }

    /**
     * Sets the overworld total time so that {@link net.minecraft.WorldProvider#getMoonPhase(long)} gives {@code phase} (0..7) at the night
     * adjusted time {@link #MOON_TIME}. Whole days are added, so the adjusted time stays the same. Returns true when the client shows the phase.
     */
    static boolean setMoonPhase(TestBot bot, int phase) throws Exception {
        bot.server(() -> {
            for (WorldServer w : MinecraftServer.getServer().worldServers) {
                if (w != null && w.provider.dimensionId == 0) {
                    long base = w.getTotalWorldTimeAtStartOfToday();
                    for (int d = 0; d < 8; d++) {
                        long t = base + MOON_TIME + 24000L * d;
                        if (w.provider.getMoonPhase(t) == phase) {
                            w.setTotalWorldTime(t, true);
                            return;
                        }
                    }
                }
            }
        });
        Minecraft mc = bot.minecraft();
        return bot.waitFor(() -> mc.theWorld != null && mc.theWorld.getMoonPhase() == phase, 200);
    }

    /** The devtest command {@code sky.capture [day] [sunset] [night] [moon]}: all four groups by default. */
    static final class Command implements DevCommand {
        private static final List<String> ALL = List.of("day", "sunset", "night", "moon");

        @Override
        public String name() {
            return "sky.capture";
        }

        @Override
        public String description() {
            return "frozen sky captures: day, sunset, night (MITE clock) and the eight moon phases at night, weather clear";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            TestBot bot = botFor(s);
            if (bot == null) {
                return;
            }
            List<String> want = args.length == 0 ? ALL : Arrays.asList(args);
            for (String w : want) {
                if (!ALL.contains(w)) {
                    s.warn("sky.capture", "unknown group '" + w + "' (use day, sunset, night, moon)");
                }
            }
            bot.stage().prepPlayer();
            s.check(museumPose(bot, 0, 0, -8, 0.0F, 12.0F), "sky pose at the museum", "the client did not reach the pose");
            Minecraft mc = bot.minecraft();
            bot.client(() -> Frozen.lockWeather(mc.theWorld, 0.0F));
            int shots = 0;
            try {
                String[][] times = {{"day", "6000"}, {"sunset", "19000"}, {"night", "0"}};
                for (String[] t : times) {
                    if (!want.contains(t[0])) {
                        continue;
                    }
                    bot.stage().setTimeOfDay(Long.parseLong(t[1]));
                    if (frozenShot(bot, s, "nr11_sky_" + t[0]) != null) {
                        shots++;
                    }
                }
                if (want.contains("moon")) {
                    for (int phase = 0; phase < 8; phase++) {
                        if (!setMoonPhase(bot, phase)) {
                            s.warn("moon phase " + phase, "the client did not show moon phase " + phase + " within 200 ticks");
                            continue;
                        }
                        if (frozenShot(bot, s, "nr11_sky_moon" + phase) != null) {
                            shots++;
                        }
                    }
                }
            } finally {
                bot.client(Frozen::unlockWeather);
            }
            s.info("sky.capture", shots + " frozen sky captures written to the capture directory");
        }
    }
}
