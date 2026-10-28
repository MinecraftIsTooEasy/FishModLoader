package net.xiaoyu233.fml.vulkan.testbot.cmd.nr13;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.Entity;
import net.minecraft.EntityList;
import net.minecraft.EntityLiving;
import net.minecraft.EntityLivingBase;
import net.minecraft.Item;
import net.minecraft.ItemStack;
import net.minecraft.Minecraft;
import net.minecraft.World;
import net.xiaoyu233.fml.vulkan.render.buffer.Batches;
import net.xiaoyu233.fml.vulkan.render.frame.FrameStats;
import net.xiaoyu233.fml.vulkan.testbot.Frozen;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/** Devtest commands of work package NR-13 (owner: NR-13): {@code nr13.zoo}, {@code nr13.stats}. */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-13";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new Zoo(), new Stats());
    }

    /**
     * Every living entity type with variants (plain, hurt, invisible, enchanted armour, name tag), frozen, in captures of
     * {@link #ROWS_PER_CAPTURE} rows each so that every entity stays inside the entity render distance of the player.
     */
    private static final class Zoo implements DevCommand {
        /** 10 columns of 3 blocks, so one row is 30 blocks wide. */
        private static final int COLS = 10;
        /** Rows per capture: 3 rows 4 blocks apart plus the 20 block player offset stay inside the ~45 block entity range at render distance 0. */
        private static final int ROWS_PER_CAPTURE = 3;
        private static final int PER_CAPTURE = COLS * ROWS_PER_CAPTURE;
        private static final int VARIANTS = 5;

        /** One entity to spawn: the registry name and the variant index (see {@link #applyVariant}). */
        private record Spec(String name, int variant) {
        }

        @Override
        public String name() {
            return "nr13.zoo";
        }

        @Override
        public String description() {
            return "spawns every living entity in frozen in-range grids (hurt/invisible/enchanted armour/name tag variants), one capture per grid";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            TestBot bot = TestBot.get();
            if (bot == null) {
                s.warn("nr13.zoo", "test bot not running");
                return;
            }
            Minecraft mc = bot.minecraft();
            double[] c = bot.stage().ensureMuseum();
            List<Spec> specs = bot.client(Zoo::specs);
            int captures = 0;
            int total = 0;
            for (int from = 0; from < specs.size(); from += PER_CAPTURE) {
                List<Spec> chunk = specs.subList(from, Math.min(specs.size(), from + PER_CAPTURE));
                int index = captures++;
                bot.stage().place(c[0], c[1] + 1.0, c[2] - 20.0, 0.0F, 20.0F);
                bot.frames(5);
                List<Entity> spawned = bot.client(() -> spawn(mc, c, chunk, s));
                total += spawned.size();
                s.info("nr13.zoo capture " + index, spawned.size() + " entities spawned");
                bot.ticks(5);
                bot.client(() -> Frozen.pinClientCounters(mc));
                bot.client(() -> Frozen.beginCapture(Frozen.DEFAULT_PARTIAL_TICKS));
                bot.frames(5);
                // Inside the frozen window every frame draws the same scene, so the counter read here belongs to the captured scene.
                int wholeFrameDraws = Batches.drawsLastFrame();
                String path = bot.screenshot(s, "nr13_zoo_" + index);
                bot.client(Frozen::endCapture);
                s.check(path != null, "nr13.zoo capture " + index, "no capture file");
                s.info("nr13.zoo draws " + index, "whole-frame draws (all phases) " + wholeFrameDraws + ", not entity-phase only");
                bot.client(() -> {
                    for (Entity e : spawned) {
                        e.setDead();
                    }
                    Frozen.releaseEntities();
                    return null;
                });
            }
            s.info("nr13.zoo", total + " entities in " + captures + " captures");
            s.warn("nr13.zoo entity gate", "NOT MEASURED: entity-phase draws <= 1/5 of phase 1 is not evaluated; Batches exposes only the whole-frame drawsLastFrame(), and no phase-1 baseline is taken in this run");
        }

        /** Every living entity type (registry order) times every variant. Client thread. */
        private static List<Spec> specs() {
            List<String> names = new ArrayList<>();
            for (int id = 1; id < 4096; id++) {
                String n = EntityList.getStringFromID(id);
                Class<?> cl = EntityList.getClassFromID(id);
                if (n != null && cl != null && EntityLivingBase.class.isAssignableFrom(cl) && !names.contains(n)) {
                    names.add(n);
                }
            }
            List<Spec> out = new ArrayList<>();
            for (String n : names) {
                for (int variant = 0; variant < VARIANTS; variant++) {
                    out.add(new Spec(n, variant));
                }
            }
            return out;
        }

        /** Spawns one chunk of the grid and returns the entities that were created. Client thread. */
        private static List<Entity> spawn(Minecraft mc, double[] c, List<Spec> chunk, Report.Section s) {
            World w = mc.theWorld;
            List<Entity> out = new ArrayList<>();
            int i = 0;
            for (Spec spec : chunk) {
                Entity e;
                try {
                    e = EntityList.createEntityByName(spec.name(), w);
                } catch (Throwable t) {
                    e = null;
                }
                if (e == null || e == mc.thePlayer) {
                    continue;
                }
                int col = i % COLS;
                int row = i / COLS;
                e.setLocationAndAngles(c[0] - COLS * 1.5 + col * 3.0, c[1], c[2] + row * 4.0, 180.0F, 0.0F);
                if (e instanceof EntityLiving l) {
                    try {
                        applyVariant(l, spec.variant());
                    } catch (Throwable t) {
                        s.warn("nr13.zoo variant " + spec.variant() + " of " + spec.name(), t.toString());
                    }
                }
                w.spawnEntityInWorld(e);
                Frozen.freezeEntity(e);
                if (e instanceof EntityLivingBase b && spec.variant() == 1) {
                    b.hurtTime = b.maxHurtTime = 10;
                }
                out.add(e);
                i++;
            }
            return out;
        }

        /** 0 plain, 1 hurt, 2 invisible, 3 iron armour + enchanted (glint), 4 name tag. */
        private static void applyVariant(EntityLiving l, int variant) {
            switch (variant) {
                case 2 -> l.setInvisible(true);
                case 3 -> {
                    ItemStack[] armour = {new ItemStack(Item.helmetIron), new ItemStack(Item.plateIron), new ItemStack(Item.legsIron), new ItemStack(Item.bootsIron)};
                    for (int slot = 0; slot < armour.length; slot++) {
                        armour[slot].addEnchantment(net.minecraft.Enchantment.unbreaking, 1);
                        l.setCurrentItemOrArmor(slot + 1, armour[slot]);
                    }
                }
                case 4 -> {
                    l.setCustomNameTag("nr13");
                    l.setAlwaysRenderNameTag(true);
                }
                default -> {
                }
            }
        }
    }

    /**
     * Prints the whole-frame draw counters of the last presented frame ({@link Batches#drawsLastFrame()}, all phases) and the
     * FrameStats frame record. It does not isolate the entity phase.
     */
    private static final class Stats implements DevCommand {
        @Override
        public String name() {
            return "nr13.stats";
        }

        @Override
        public String description() {
            return "prints the whole-frame draw counts of the last frame (all phases, not entity-only) from Batches.drawsLastFrame / FrameStats";
        }

        @Override
        public void run(Report.Section s, String[] args) {
            FrameStats.Frame f = FrameStats.latest();
            s.info("nr13.stats", "Batches.drawsLastFrame=" + Batches.drawsLastFrame() + " quads=" + Batches.quadsLastFrame()
                + " (whole frame, all phases)"
                + (f != null ? " FrameStats.frame=" + f.index() + " draws=" + f.draws() + " encoded=" + f.encodedDraws() : " (no frame yet)"));
        }
    }
}
