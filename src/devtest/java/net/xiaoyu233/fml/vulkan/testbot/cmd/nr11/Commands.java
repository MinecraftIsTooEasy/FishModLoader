package net.xiaoyu233.fml.vulkan.testbot.cmd.nr11;

import java.util.List;
import net.minecraft.BiomeGenBase;
import net.minecraft.Minecraft;
import net.minecraft.WorldServer;
import net.minecraft.server.MinecraftServer;
import net.xiaoyu233.fml.vulkan.testbot.Frozen;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.Stage;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/**
 * Devtest commands of work package NR-11 (sky, clouds, weather, selection box, block damage): {@code sky.capture}, {@code clouds.fly200},
 * {@code weather.capture}, {@code selbox.capture}, {@code damage.capture}.
 *
 * <p>All commands run on the bot script thread in the {@code 06_commands} step (or as a scene set-up through {@code cmd name=...}); every
 * frame-dependent read or write goes through {@link TestBot#client}. Frozen screenshots use the same capture set-up as the scripted
 * scenes ({@code SkyCapture.frozenShot}). Each command restores the museum pose it moved the player from.
 */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-11";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new SkyCapture.Command(), new CloudFlyCommand(), new WeatherCommand(), new SelectionBoxCommand(), new DamageCommand());
    }

    /** Flight distance of {@code clouds.fly200}. */
    static final double FLY_DISTANCE = 200.0;

    /** {@code clouds.fly200}: frozen cloud capture, a 200 block teleport along x, then the step-wise cloud recompile is asserted and logged. */
    static final class CloudFlyCommand implements DevCommand {
        @Override
        public String name() {
            return "clouds.fly200";
        }

        @Override
        public String description() {
            return "teleport 200 blocks along x: frozen cloud captures before/after, asserts the step-wise cloud recompile and logs it";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            TestBot bot = SkyCapture.botFor(s);
            if (bot == null) {
                return;
            }
            Stage stage = bot.stage();
            Minecraft mc = bot.minecraft();
            stage.prepPlayer();
            s.check(SkyCapture.museumPose(bot, 0, 0, -8, 0.0F, 12.0F), "clouds pose at the museum", "the client did not reach the pose");
            double[] start = bot.client(() -> new double[] {mc.thePlayer.posX, mc.thePlayer.boundingBox.minY, mc.thePlayer.posZ,
                mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch});
            try {
                SkyCapture.frozenShot(bot, s, "nr11_clouds_before");
                double before = bot.client(() -> mc.renderGlobal.last_cloud_compile_x);
                boolean placed = stage.place(start[0] + FLY_DISTANCE, start[1], start[2], (float) start[3], (float) start[4]);
                s.check(placed, "clouds.fly200 teleport to x+200", "the client did not reach x+200 within 100 ticks");
                bot.frames(5);
                boolean recompiled = bot.waitFor(() -> mc.renderGlobal.last_cloud_compile_x != before
                    && Math.abs(mc.renderGlobal.last_cloud_compile_x - mc.thePlayer.posX) <= 2.0, 300);
                double compiledX = bot.client(() -> mc.renderGlobal.last_cloud_compile_x);
                double playerX = bot.client(() -> mc.thePlayer.posX);
                s.check(recompiled, "clouds recompiled after flying 200 blocks along x",
                    "last_cloud_compile_x " + SkyCapture.fmt(compiledX) + " (was " + SkyCapture.fmt(before) + "), player x " + SkyCapture.fmt(playerX) + " within 300 ticks");
                s.info("clouds.fly200", "cloud compile x " + SkyCapture.fmt(before) + " -> " + SkyCapture.fmt(compiledX) + ", player x " + SkyCapture.fmt(playerX)
                    + ", recompile " + (recompiled ? "seen" : "missing"));
                SkyCapture.frozenShot(bot, s, "nr11_clouds_fly200");
            } finally {
                stage.place(start[0], start[1], start[2], (float) start[3], (float) start[4]);
            }
        }
    }

    /** {@code weather.capture}: rain at the museum and snow at the nearest column with a snowy biome (search within 256 blocks, server side). */
    static final class WeatherCommand implements DevCommand {
        private static final int SEARCH_RADIUS = 256;
        private static final int SEARCH_STEP = 16;

        @Override
        public String name() {
            return "weather.capture";
        }

        @Override
        public String description() {
            return "frozen rain capture at the museum and a frozen snow capture at the nearest snowy biome within 256 blocks";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            TestBot bot = SkyCapture.botFor(s);
            if (bot == null) {
                return;
            }
            Stage stage = bot.stage();
            Minecraft mc = bot.minecraft();
            stage.prepPlayer();
            s.check(SkyCapture.museumPose(bot, 0, 0, -8, 0.0F, 12.0F), "weather pose at the museum", "the client did not reach the pose");
            try {
                bot.client(() -> Frozen.lockWeather(mc.theWorld, 1.0F));
                SkyCapture.frozenShot(bot, s, "nr11_weather_rain");

                double[] spawn = stage.spawn();
                double[] spot = bot.server(() -> findSnowColumn(spawn));
                if (spot == null) {
                    s.warn("weather.capture snow", "no snowy biome within " + SEARCH_RADIUS + " blocks of spawn: snow capture skipped");
                    return;
                }
                boolean placed = stage.place(spot[0], spot[1], spot[2], 0.0F, 5.0F);
                s.check(placed, "snow pose at " + SkyCapture.fmt(spot[0]) + "," + SkyCapture.fmt(spot[2]), "the client did not reach the snowy column");
                boolean snowy = bot.client(() -> {
                    BiomeGenBase b = mc.theWorld.getBiomeGenForCoords((int) Math.floor(mc.thePlayer.posX), (int) Math.floor(mc.thePlayer.posZ));
                    return b != null && b.getEnableSnow();
                });
                s.check(snowy, "client biome at the snow pose enables snow", "the client biome is not snowy");
                SkyCapture.frozenShot(bot, s, "nr11_weather_snow");
            } finally {
                bot.client(Frozen::unlockWeather);
                SkyCapture.museumPose(bot, 0, 0, -8, 0.0F, 12.0F);
            }
        }

        /** Server thread: first column (rings around spawn {x, z}) whose loaded biome enables snow, as {feet x, feet y, z}; null when none. */
        private static double[] findSnowColumn(double[] spawn) {
            WorldServer w = null;
            for (WorldServer world : MinecraftServer.getServer().worldServers) {
                if (world != null && world.provider.dimensionId == 0) {
                    w = world;
                }
            }
            if (w == null) {
                return null;
            }
            for (int r = 0; r <= SEARCH_RADIUS; r += SEARCH_STEP) {
                for (int dx = -r; dx <= r; dx += SEARCH_STEP) {
                    for (int dz = -r; dz <= r; dz += SEARCH_STEP) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) {
                            continue;
                        }
                        int x = (int) spawn[0] + dx;
                        int z = (int) spawn[1] + dz;
                        if (!w.blockExists(x, 0, z)) {
                            continue;
                        }
                        BiomeGenBase b = w.getBiomeGenForCoords(x, z);
                        if (b != null && b.getEnableSnow()) {
                            return new double[] {x + 0.5, w.getHeightValue(x, z) + 6.0, z + 0.5};
                        }
                    }
                }
            }
            return null;
        }
    }

    /** {@code selbox.capture}: frozen captures with the crosshair on a block (selection box, LINES) at two pitches. */
    static final class SelectionBoxCommand implements DevCommand {
        @Override
        public String name() {
            return "selbox.capture";
        }

        @Override
        public String description() {
            return "frozen captures of the block selection box (crosshair on a museum block at two pitches)";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            TestBot bot = SkyCapture.botFor(s);
            if (bot == null) {
                return;
            }
            Minecraft mc = bot.minecraft();
            bot.stage().prepPlayer();
            for (float pitch : new float[] {30.0F, 60.0F}) {
                s.check(SkyCapture.museumPose(bot, 0, 0, -8, 0.0F, pitch), "selbox pose pitch " + pitch, "the client did not reach the pose");
                String hit = bot.client(() -> mc.objectMouseOver == null ? "none" : mc.objectMouseOver.isBlock()
                    ? "block " + mc.objectMouseOver.block_hit_x + "," + mc.objectMouseOver.block_hit_y + "," + mc.objectMouseOver.block_hit_z : "not a block");
                s.check(hit.startsWith("block"), "crosshair on a block at pitch " + pitch, "objectMouseOver is " + hit + ": no selection box to capture");
                s.info("selbox pitch " + pitch, "crosshair " + hit);
                SkyCapture.frozenShot(bot, s, "nr11_selbox_p" + (int) pitch);
            }
        }
    }

    /** {@code damage.capture}: block damage overlay on the block under the crosshair at three stages, then the damage entries are removed. */
    static final class DamageCommand implements DevCommand {
        private static final int[] STAGES = {2, 5, 9};

        @Override
        public String name() {
            return "damage.capture";
        }

        @Override
        public String description() {
            return "frozen captures of the block damage overlay (stages 2, 5, 9 of 10) on the block under the crosshair";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            TestBot bot = SkyCapture.botFor(s);
            if (bot == null) {
                return;
            }
            Minecraft mc = bot.minecraft();
            bot.stage().prepPlayer();
            s.check(SkyCapture.museumPose(bot, 0, 0, -8, 0.0F, 30.0F), "damage pose at the museum", "the client did not reach the pose");
            int[] target = bot.client(() -> mc.objectMouseOver != null && mc.objectMouseOver.isBlock()
                ? new int[] {mc.objectMouseOver.block_hit_x, mc.objectMouseOver.block_hit_y, mc.objectMouseOver.block_hit_z} : null);
            if (target == null) {
                s.fail("damage.capture", "crosshair is not on a block: no block to damage");
                return;
            }
            final int x = target[0];
            final int y = target[1];
            final int z = target[2];
            s.info("damage target", "block " + x + "," + y + "," + z);
            try {
                for (int stage : STAGES) {
                    bot.client(() -> mc.renderGlobal.destroyBlockPartially(mc.thePlayer.entityId, x, y, z, stage));
                    SkyCapture.frozenShot(bot, s, "nr11_damage_" + stage);
                }
            } finally {
                // -2 removes the damage entries of that block
                bot.client(() -> mc.renderGlobal.destroyBlockPartially(mc.thePlayer.entityId, x, y, z, -2));
            }
        }
    }
}
