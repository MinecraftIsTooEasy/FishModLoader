package net.xiaoyu233.fml.vulkan.testbot.cmd.nr15;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;
import net.minecraft.Block;
import net.minecraft.EffectRenderer;
import net.minecraft.EntityBreakingFX;
import net.minecraft.EntityDiggingFX;
import net.minecraft.EntityFX;
import net.minecraft.EntityFireworkSparkFX;
import net.minecraft.EntityFireworkStarterFX;
import net.minecraft.EntityFootStepFX;
import net.minecraft.EntityLargeExplodeFX;
import net.minecraft.EnumFace;
import net.minecraft.Item;
import net.minecraft.Minecraft;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.render.frame.FrameStats;
import net.xiaoyu233.fml.vulkan.render.particle.ParticleFastPath;
import net.xiaoyu233.fml.vulkan.testbot.Frozen;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/**
 * Devtest commands of work package NR-15 (native particles): {@code nr15.explosion}, {@code nr15.footsteps}, {@code nr15.blockbreak},
 * {@code nr15.firework}, {@code nr15.stress}, {@code nr15.counts}, {@code nr15.selfcheck}. All spawn from a seeded {@link Random}; the capture commands
 * (all but stress/counts) clear the effect renderer, spawn, pin the particles, open the frozen capture window
 * (ARCHITECTURE-NATIVE 7.2: {@link Frozen#beginCapture}, client ticks paused) and leave it open for the capture; the next NR-15 command
 * closes it. Without {@code fml.vulkan.frozen} the particles get a huge life span instead, so they still do not change between frames.
 */
public final class Commands implements DevCommandProvider {
    private static final long SEED = 0x5EED15L;
    private static final int FOREVER = 1_000_000;

    @Override
    public String packageId() {
        return "NR-15";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(
            new Cmd("nr15.explosion", "EntityLargeExplodeFX burst (layer 3, polygon offset, brightness 240), frozen", Commands::explosion),
            new Cmd("nr15.footsteps", "EntityFootStepFX line (layer 3, footprint.png, blend), frozen", Commands::footsteps),
            new Cmd("nr15.blockbreak", "addBlockDestroyEffects on stone + block hit effects (layer 1), frozen", Commands::blockBreak),
            new Cmd("nr15.firework", "EntityFireworkStarterFX burst + EntityFireworkSparkFX ring (layer 0), frozen", Commands::firework),
            new Cmd("nr15.stress", "4 layers x 4000 particles, FPS over N frames (arg: N, default 300)", Commands::stress),
            new Cmd("nr15.counts", "seeded spawn + 600 ticks, particle count per layer (compare particles.fast=true/false)", Commands::counts),
            new Cmd("nr15.selfcheck", "ENT-02 compaction equivalence (selfCheck) + cull margin check (cullSelfCheck), CPU only (arg: N particles per run, default 200)", Commands::selfCheck));
    }

    private interface Body {
        void run(Report.Section s, String[] args, TestBot bot) throws Exception;
    }

    private record Cmd(String name, String description, Body body) implements DevCommand {
        @Override
        public void run(Report.Section section, String[] args) throws Exception {
            TestBot bot = TestBot.get();
            if (bot == null) {
                section.fail(name, "testbot not running");
                return;
            }
            bot.client(Frozen::endCapture);
            body.run(section, args, bot);
        }
    }

    // ------------------------------------------------------------------ helpers (client thread)

    private static boolean ready(Report.Section s, String name, Minecraft mc) {
        if (mc.theWorld == null || mc.thePlayer == null || mc.effectRenderer == null) {
            s.fail(name, "no world / player / effect renderer (enter a world first)");
            return false;
        }
        return true;
    }

    /** A point 4 blocks in front of the player at foot height. */
    private static double[] anchor(Minecraft mc) {
        double yaw = Math.toRadians(mc.thePlayer.rotationYaw);
        return new double[] {mc.thePlayer.posX - Math.sin(yaw) * 4.0, mc.thePlayer.posY, mc.thePlayer.posZ + Math.cos(yaw) * 4.0};
    }

    private static void clear(Minecraft mc) {
        mc.effectRenderer.clearEffects(mc.theWorld);
    }

    /** Seeds the unseeded constructor randomness and keeps the particle alive for the frozen window. */
    private static <T extends EntityFX> T pin(T p, Random rng) {
        Frozen.pinParticle(p, rng);
        Frozen.setField(p, "particleMaxAge", FOREVER);
        return p;
    }

    private static void freeze() {
        Frozen.beginCapture(Frozen.DEFAULT_PARTIAL_TICKS);
    }

    private static void finish(Report.Section s, String name, TestBot bot, String what) throws Exception {
        String stats = bot.client(() -> Minecraft.getMinecraft().effectRenderer.getStatsString());
        bot.frames(3);
        s.info(name, what + "; particles " + stats + "; frozen=" + Frozen.active() + " fast=" + VulkanConfig.particlesFast());
        s.ok(name);
    }

    // ------------------------------------------------------------------ scenes

    private static void explosion(Report.Section s, String[] args, TestBot bot) throws Exception {
        bot.client(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (!ready(s, "nr15.explosion", mc)) {
                return;
            }
            clear(mc);
            Random rng = new Random(SEED);
            double[] a = anchor(mc);
            for (int i = 0; i < 12; i++) {
                double x = a[0] + (rng.nextDouble() - 0.5) * 6.0;
                double y = a[1] + 1.0 + rng.nextDouble() * 3.0;
                double z = a[2] + (rng.nextDouble() - 0.5) * 6.0;
                mc.effectRenderer.addEffect(pin(new EntityLargeExplodeFX(mc.getTextureManager(), mc.theWorld, x, y, z, rng.nextDouble(), 0.0, 0.0), rng));
            }
            freeze();
        });
        finish(s, "nr15.explosion", bot, "12 EntityLargeExplodeFX");
    }

    private static void footsteps(Report.Section s, String[] args, TestBot bot) throws Exception {
        bot.client(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (!ready(s, "nr15.footsteps", mc)) {
                return;
            }
            clear(mc);
            Random rng = new Random(SEED);
            double[] a = anchor(mc);
            for (int i = 0; i < 16; i++) {
                double x = a[0] - 4.0 + i * 0.5 + (rng.nextDouble() - 0.5) * 0.1;
                double z = a[2] + (i % 2 == 0 ? -0.2 : 0.2);
                EntityFootStepFX p = new EntityFootStepFX(mc.getTextureManager(), mc.theWorld, x, a[1] + 0.01, z);
                Frozen.setField(p, "footstepMaxAge", FOREVER);
                Frozen.setField(p, "footstepAge", rng.nextInt(100));
                mc.effectRenderer.addEffect(p);
            }
            freeze();
        });
        finish(s, "nr15.footsteps", bot, "16 EntityFootStepFX in a line");
    }

    private static void blockBreak(Report.Section s, String[] args, TestBot bot) throws Exception {
        bot.client(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (!ready(s, "nr15.blockbreak", mc)) {
                return;
            }
            clear(mc);
            double[] a = anchor(mc);
            int x = (int) Math.floor(a[0]);
            int y = (int) Math.floor(a[1]) + 1;
            int z = (int) Math.floor(a[2]);
            EffectRenderer er = mc.effectRenderer;
            er.addBlockDestroyEffects(x, y, z, Block.stone.blockID, 0, 0);
            for (EnumFace face : new EnumFace[] {EnumFace.TOP, EnumFace.NORTH}) {
                er.addBlockHitEffects(x + 3, y, z, face);
            }
            Random rng = new Random(SEED);
            for (java.lang.Object o : layer(er, 1)) {
                pin((EntityFX) o, rng);
            }
            freeze();
        });
        finish(s, "nr15.blockbreak", bot, "addBlockDestroyEffects(stone) + 2 hit effects");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> layer(EffectRenderer er, int i) {
        Object f = Frozen.getField(er, "fxLayers");
        return f instanceof List[] l ? (List<Object>) l[i] : List.of();
    }

    private static void firework(Report.Section s, String[] args, TestBot bot) throws Exception {
        bot.client(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (!ready(s, "nr15.firework", mc)) {
                return;
            }
            clear(mc);
            Random rng = new Random(SEED);
            double[] a = anchor(mc);
            EffectRenderer er = mc.effectRenderer;
            NBTTagCompound tag = new NBTTagCompound();
            NBTTagList explosions = new NBTTagList();
            NBTTagCompound ex = new NBTTagCompound();
            ex.setByte("Type", (byte) 1);
            ex.setBoolean("Trail", true);
            ex.setIntArray("Colors", new int[] {0xFF2020, 0x2040FF});
            ex.setIntArray("FadeColors", new int[] {0xFFFF40});
            explosions.appendTag(ex);
            tag.setTag("Explosions", explosions);
            er.addEffect(pin(new EntityFireworkStarterFX(mc.theWorld, a[0], a[1] + 6, a[2], 0, 0, 0, er, tag), rng));
            for (int i = 0; i < 64; i++) {
                double ang = i * Math.PI * 2 / 64;
                EntityFireworkSparkFX p = new EntityFireworkSparkFX(mc.theWorld, a[0], a[1] + 5, a[2], 0, 0, 0, er);
                p.setPosition(a[0] + Math.cos(ang) * 2.5, a[1] + 5 + Math.sin(ang) * 2.5, a[2] + rng.nextDouble() * 0.4);
                p.setColour(i % 2 == 0 ? 0xFF2020 : 0x2040FF);
                pin(p, rng);
                er.addEffect(p);
            }
            freeze();
        });
        finish(s, "nr15.firework", bot, "1 starter + 64 EntityFireworkSparkFX ring");
    }

    private static int spawnStress(Minecraft mc, Random rng, double[] a, int perLayer) {
        EffectRenderer er = mc.effectRenderer;
        for (int i = 0; i < perLayer; i++) {
            double x = a[0] + (rng.nextDouble() - 0.5) * 12.0;
            double y = a[1] + rng.nextDouble() * 6.0;
            double z = a[2] + (rng.nextDouble() - 0.5) * 12.0;
            EntityFX p0 = new EntityFX(mc.theWorld, x, y, z, 0, 0, 0);
            p0.setParticleTextureIndex(rng.nextInt(8));
            er.addEffect(pin(p0, rng));
            er.addEffect(pin(new EntityDiggingFX(mc.theWorld, x, y, z, 0, 0, 0, Block.stone, 0), rng));
            er.addEffect(pin(new EntityBreakingFX(mc.theWorld, x, y, z, Item.snowball), rng));
            EntityFootStepFX f = new EntityFootStepFX(mc.getTextureManager(), mc.theWorld, x, y, z);
            Frozen.setField(f, "footstepMaxAge", FOREVER);
            er.addEffect(f);
        }
        return perLayer * 4;
    }

    private static void stress(Report.Section s, String[] args, TestBot bot) throws Exception {
        int frames = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        String stats = bot.client(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (!ready(s, "nr15.stress", mc)) {
                return null;
            }
            clear(mc);
            spawnStress(mc, new Random(SEED), anchor(mc), 4000);
            freeze();
            return mc.effectRenderer.getStatsString();
        });
        if (stats == null) {
            return;
        }
        bot.frames(30);
        long f0 = FrameStats.frameCount();
        long t0 = System.nanoTime();
        bot.frames(frames);
        long dt = System.nanoTime() - t0;
        long n = FrameStats.frameCount() - f0;
        double fps = n * 1e9 / Math.max(1, dt);
        s.info("nr15.stress", "particles " + stats + "; " + n + " frames in " + String.format("%.2f", dt / 1e9) + " s = " + String.format("%.1f", fps)
            + " FPS (fast=" + VulkanConfig.particlesFast() + ")");
        s.ok("nr15.stress");
    }

    private static void counts(Report.Section s, String[] args, TestBot bot) throws Exception {
        bot.client(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (!ready(s, "nr15.counts", mc)) {
                return;
            }
            clear(mc);
            Random rng = new Random(SEED);
            double[] a = anchor(mc);
            EffectRenderer er = mc.effectRenderer;
            // life spans 20..1019 ticks (no pinning of the age): some particles die during the 600 ticks, others outlive them, so the
            // t500/t600 samples are non-zero
            for (int i = 0; i < 1500; i++) {
                double x = a[0] + (rng.nextDouble() - 0.5) * 10.0;
                double y = a[1] + rng.nextDouble() * 5.0;
                double z = a[2] + (rng.nextDouble() - 0.5) * 10.0;
                EntityFX p0 = new EntityFX(mc.theWorld, x, y, z, 0, 0, 0);
                p0.setParticleTextureIndex(rng.nextInt(8));
                Frozen.setField(p0, "particleMaxAge", 20 + rng.nextInt(1000));
                er.addEffect(p0);
                EntityDiggingFX d = new EntityDiggingFX(mc.theWorld, x, y, z, 0, 0, 0, Block.stone, 0);
                Frozen.setField(d, "particleMaxAge", 20 + rng.nextInt(1000));
                er.addEffect(d);
                EntityBreakingFX b = new EntityBreakingFX(mc.theWorld, x, y, z, Item.snowball);
                Frozen.setField(b, "particleMaxAge", 20 + rng.nextInt(1000));
                er.addEffect(b);
                EntityFootStepFX f = new EntityFootStepFX(mc.getTextureManager(), mc.theWorld, x, y, z);
                Frozen.setField(f, "footstepMaxAge", 20 + rng.nextInt(1000));
                er.addEffect(f);
            }
            // 3000 more layer-0 EntityFX: layer 0 gets 4500 EntityFX (+200 sparks below), so the 4000 cap (drop-oldest) is hit in both modes
            for (int i = 0; i < 3000; i++) {
                double x = a[0] + (rng.nextDouble() - 0.5) * 10.0;
                double y = a[1] + rng.nextDouble() * 5.0;
                double z = a[2] + (rng.nextDouble() - 0.5) * 10.0;
                EntityFX p0 = new EntityFX(mc.theWorld, x, y, z, 0, 0, 0);
                p0.setParticleTextureIndex(rng.nextInt(8));
                Frozen.setField(p0, "particleMaxAge", 20 + rng.nextInt(1000));
                er.addEffect(p0);
            }
            // 200 trail firework sparks (layer 0): their onUpdate calls addEffect on the effect renderer while the layer is being updated,
            // the one real path of the index semantics. Their child sparks take MITE's unseeded Entity.rand lifetimes, so this part is
            // checked for the same counts only in the in-process selfCheck; here it is a smoke test
            for (int i = 0; i < 200; i++) {
                double x = a[0] + (rng.nextDouble() - 0.5) * 10.0;
                double y = a[1] + 2.0 + rng.nextDouble() * 4.0;
                double z = a[2] + (rng.nextDouble() - 0.5) * 10.0;
                EntityFireworkSparkFX sp = new EntityFireworkSparkFX(mc.theWorld, x, y, z, 0, 0, 0, er);
                sp.setTrail(true);
                sp.setColour(i % 2 == 0 ? 0xFF2020 : 0x2040FF);
                Frozen.setField(sp, "particleMaxAge", 200 + rng.nextInt(800));
                er.addEffect(sp);
            }
        });
        String before = bot.client(() -> Minecraft.getMinecraft().effectRenderer.getStatsString());
        List<String> samples = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            bot.ticks(100);
            samples.add("t" + (i + 1) * 100 + " " + bot.client(() -> Minecraft.getMinecraft().effectRenderer.getStatsString()));
        }
        s.info("nr15.counts", "fast=" + VulkanConfig.particlesFast() + "; spawn " + before + "; " + String.join(" | ", samples));
        s.ok("nr15.counts");
    }

    /**
     * Runs the two pure-CPU self checks of {@link ParticleFastPath}; no world or client thread is needed. {@code cullSelfCheck} is
     * package-private there, so it is reached by reflection.
     */
    private static void selfCheck(Report.Section s, String[] args, TestBot bot) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        try {
            String equiv = ParticleFastPath.selfCheck(n, SEED);
            if (equiv == null) {
                s.ok("nr15.selfcheck.equivalence");
            } else {
                s.fail("nr15.selfcheck.equivalence", equiv);
            }
            Method cull = ParticleFastPath.class.getDeclaredMethod("cullSelfCheck", long.class);
            cull.setAccessible(true);
            String margin = (String) cull.invoke(null, SEED);
            if (margin == null) {
                s.ok("nr15.selfcheck.cull");
            } else {
                s.fail("nr15.selfcheck.cull", margin);
            }
        } catch (Exception | LinkageError e) {
            s.exception("nr15.selfcheck", e);
        }
    }
}
