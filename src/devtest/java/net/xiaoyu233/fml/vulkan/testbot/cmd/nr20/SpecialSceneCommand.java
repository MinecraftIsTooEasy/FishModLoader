package net.xiaoyu233.fml.vulkan.testbot.cmd.nr20;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.Block;
import net.minecraft.Enchantment;
import net.minecraft.EntityCreeper;
import net.minecraft.EntityDragon;
import net.minecraft.EntityEnderman;
import net.minecraft.EntityFishHook;
import net.minecraft.EntityLightningBolt;
import net.minecraft.EntityOtherPlayerMP;
import net.minecraft.EntityWither;
import net.minecraft.Entity;
import net.minecraft.Item;
import net.minecraft.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.Minecraft;
import net.minecraft.ResourceLocation;
import net.minecraft.ThreadDownloadImageData;
import net.minecraft.WorldClient;
import net.minecraft.WorldServer;
import net.xiaoyu233.fml.vulkan.testbot.Frozen;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.Stage;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * Frozen scenes of the NR-20 special renderers. Args: {@code <kind> [variant] [back]}, kinds: {@code portal}, {@code dragon [alive|dying100|dying190]},
 * {@code creeper} (charged), {@code wither} (armoured), {@code enderman} (carrying a block), {@code player [armor|skull|block|bow] [back]}, {@code fish}, {@code lightning}.
 *
 * <p>Entities are spawned on the CLIENT world only (ids from 7,000,000: the server never sees them, so no AI, no despawn) in front of the camera that the scene's
 * {@code pose} established, every pinned with {@link Frozen#freezeEntity} and fixed {@code ticksExisted}; the lightning bolt is a no-op-tick subclass (a real bolt dies
 * after a few ticks) with a fixed {@code boltVertex} seed and needs {@code rain=1} in the scene line (MITE draws bolts only at rain strength >= 0.5). The end portal is
 * a 3x3 of {@code Block.endPortal} on the museum platform, at (centre x - 1..+1, centre z - 5..-3). Everything is removed again after the capture window.
 */
final class SpecialSceneCommand implements DevCommand {
    private static final int ID_BASE = 7_000_000;
    private static int nextId = ID_BASE;
    private static Batch current;
    private static ResourceLocation capeLocation;

    @Override
    public String name() {
        return "specialScene";
    }

    @Override
    public String description() {
        return "NR-20 frozen special-renderer scene: specialScene <portal|dragon|creeper|wither|enderman|player|fish|lightning> [variant] [back]";
    }

    /** What one command spawned (client entities, weather effects, server blocks) and how to take it away. */
    private static final class Batch {
        final List<Entity> entities = new ArrayList<>();
        final List<EntityLightningBolt> bolts = new ArrayList<>();
        final List<int[]> blocks = new ArrayList<>();
        boolean cleaned;

        synchronized void clean(TestBot bot) {
            if (cleaned) {
                return;
            }
            cleaned = true;
            try {
                bot.client(() -> {
                    WorldClient w = Minecraft.getMinecraft().theWorld;
                    if (w != null) {
                        for (Entity e : entities) {
                            w.removeEntityFromWorld(e.entityId);
                        }
                        for (EntityLightningBolt b : bolts) {
                            b.setDead();
                            w.weatherEffects.remove(b);
                        }
                    }
                });
                if (!blocks.isEmpty()) {
                    bot.server(() -> {
                        WorldServer w = MinecraftServer.getServer().worldServerForDimension(Minecraft.getMinecraft().thePlayer.dimension);
                        for (int[] b : blocks) {
                            w.setBlock(b[0], b[1], b[2], 0, 0, 2);
                        }
                    });
                }
            } catch (Exception ignored) {
                // run ended or aborted: the world goes away with it
            }
        }
    }

    /** {@code specialClear}: removes what the last scene spawned. */
    static final class Clear implements DevCommand {
        @Override
        public String name() {
            return "specialClear";
        }

        @Override
        public String description() {
            return "removes the entities / blocks of the last NR-20 specialScene";
        }

        @Override
        public void run(Report.Section s, String[] args) {
            Batch b;
            synchronized (SpecialSceneCommand.class) {
                b = current;
                current = null;
            }
            if (b != null) {
                b.clean(TestBot.get());
            }
            s.ok("specialClear");
        }
    }

    @Override
    public void run(Report.Section s, String[] args) throws Exception {
        TestBot bot = TestBot.get();
        Stage stage = bot == null ? null : bot.stage();
        if (stage == null) {
            s.fail("specialScene", "the bot has no stage (no world)");
            return;
        }
        if (args.length == 0) {
            s.fail("specialScene", "usage: " + description());
            return;
        }
        String kind = args[0].toLowerCase(Locale.ROOT);
        String variant = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        boolean back = args.length > 2 && args[2].equalsIgnoreCase("back") || variant.equals("back");
        Batch prev;
        Batch batch = new Batch();
        synchronized (SpecialSceneCommand.class) {
            prev = current;
            current = batch;
        }
        if (prev != null) {
            prev.clean(bot);
        }
        Minecraft mc = Minecraft.getMinecraft();
        double[] c = stage.ensureMuseum();
        if (kind.equals("portal")) {
            portal(bot, mc, batch, c);
        } else {
            bot.client(() -> spawn(mc, batch, kind, variant, back));
        }
        // the entities / blocks live until the capture window of this scene closed (or 20 s in a run without capture windows)
        Thread t = new Thread(() -> watch(bot, batch), "nr20-scene-cleanup");
        t.setDaemon(true);
        t.start();
        s.info("specialScene " + kind + (variant.isEmpty() ? "" : " " + variant), String.format(Locale.ROOT, "%d entities, %d bolts, %d blocks, frozen=%b",
                batch.entities.size(), batch.bolts.size(), batch.blocks.size(), Frozen.active()));
    }

    private static void watch(TestBot bot, Batch batch) {
        try {
            long end = System.currentTimeMillis() + 180_000;
            boolean seen = false;
            long started = System.currentTimeMillis();
            while (System.currentTimeMillis() < end) {
                if (Frozen.captureWindowOpen()) {
                    seen = true;
                } else if (seen) {
                    break;
                } else if (!Frozen.active() && System.currentTimeMillis() - started > 20_000) {
                    break;
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException ignored) {
            return;
        }
        synchronized (SpecialSceneCommand.class) {
            if (current == batch) {
                current = null;
            }
        }
        batch.clean(bot);
    }

    // ------------------------------------------------------------------ portal

    private static void portal(TestBot bot, Minecraft mc, Batch batch, double[] c) throws Exception {
        int cx = (int) Math.floor(c[0]);
        int cz = (int) Math.floor(c[2]);
        int y = (int) c[1];
        List<int[]> blocks = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -5; dz <= -3; dz++) {
                blocks.add(new int[] {cx + dx, y, cz + dz});
            }
        }
        batch.blocks.addAll(blocks);
        bot.server(() -> {
            WorldServer w = MinecraftServer.getServer().worldServerForDimension(mc.thePlayer.dimension);
            for (int[] b : blocks) {
                w.setBlock(b[0], b[1], b[2], Block.endPortal.blockID, 0, 2);
            }
        });
        int[] first = blocks.get(0);
        bot.waitFor(() -> mc.theWorld.getBlockId(first[0], first[1], first[2]) == Block.endPortal.blockID, 100);
        bot.waitFor(() -> mc.theWorld.getBlockId(blocks.get(8)[0], blocks.get(8)[1], blocks.get(8)[2]) == Block.endPortal.blockID, 100);
        bot.frames(3);
    }

    // ------------------------------------------------------------------ client entities

    private static void spawn(Minecraft mc, Batch batch, String kind, String variant, boolean back) {
        WorldClient w = mc.theWorld;
        double yaw = mc.thePlayer.rotationYaw;
        double rad = Math.toRadians(yaw);
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        double px = mc.thePlayer.posX;
        double pz = mc.thePlayer.posZ;
        double feet = mc.thePlayer.boundingBox.minY;
        float faceCamera = (float) yaw + 180.0F;
        switch (kind) {
            case "dragon" -> {
                EntityDragon d = new EntityDragon(w);
                place(d, px + fx * 22, feet + 3, pz + fz * 22, (float) yaw + 90.0F);
                for (double[] r : d.ringBuffer) {
                    r[0] = d.rotationYaw;
                    r[1] = d.posY;
                    r[2] = d.rotationYaw;
                }
                d.ringBufferIndex = 0;
                d.prevAnimTime = 0.3F;
                d.animTime = 0.3F;
                d.deathTicks = variant.equals("dying100") ? 100 : variant.equals("dying190") ? 190 : 0;
                add(w, batch, d);
            }
            case "creeper" -> {
                EntityCreeper e = new EntityCreeper(w);
                place(e, px + fx * 4, feet, pz + fz * 4, faceCamera);
                e.getDataWatcher().updateObject(17, (byte) 1);
                add(w, batch, e);
            }
            case "wither" -> {
                EntityWither e = new EntityWither(w);
                place(e, px + fx * 7, feet, pz + fz * 7, faceCamera);
                e.func_82215_s(0);
                e.setHealth(e.getMaxHealth() / 4.0F); // below half: armoured (the charged-armour pass)
                add(w, batch, e);
            }
            case "enderman" -> {
                EntityEnderman e = new EntityEnderman(w);
                place(e, px + fx * 5, feet, pz + fz * 5, faceCamera);
                e.setCarried(Block.pumpkin.blockID);
                e.setCarryingData(0);
                add(w, batch, e);
            }
            case "player" -> {
                EntityOtherPlayerMP p = player(w, px + fx * 4, feet, pz + fz * 4, back ? (float) yaw : faceCamera);
                switch (variant) {
                    case "skull" -> {
                        p.inventory.armorInventory[3] = new ItemStack(Item.skull, 1, 1);
                        p.inventory.mainInventory[0] = new ItemStack(Item.bow);
                    }
                    case "block" -> p.inventory.mainInventory[0] = new ItemStack(Block.dirt);
                    case "bow" -> p.inventory.mainInventory[0] = new ItemStack(Item.bow);
                    default -> {
                        p.inventory.armorInventory[3] = new ItemStack(Item.helmetIron);
                        ItemStack chest = new ItemStack(Item.plateIron);
                        chest.addEnchantment(Enchantment.protection, 1);
                        p.inventory.armorInventory[2] = chest;
                        p.inventory.armorInventory[1] = new ItemStack(Item.legsIron);
                        p.inventory.armorInventory[0] = new ItemStack(Item.bootsIron);
                        p.inventory.mainInventory[0] = new ItemStack(Item.swordIron);
                    }
                }
                p.inventory.currentItem = 0;
                add(w, batch, p);
            }
            case "fish" -> {
                EntityOtherPlayerMP p = player(w, px + fx * 6, feet, pz + fz * 6, faceCamera);
                p.inventory.mainInventory[0] = new ItemStack(Item.fishingRodIron);
                p.inventory.currentItem = 0;
                add(w, batch, p);
                EntityFishHook h = new EntityFishHook(w, px + fx * 3.0 - fz * 1.0, feet + 0.5, pz + fz * 3.0 + fx * 1.0, p);
                Frozen.freezeEntity(h);
                h.ticksExisted = 100;
                add(w, batch, h);
            }
            case "lightning" -> {
                Frozen.lockWeather(w, 1.0F);
                double x = px + fx * 10;
                double z = pz + fz * 10;
                EntityLightningBolt b = new EntityLightningBolt(w, x, feet, z) {
                    @Override
                    public void onUpdate() {
                        // frozen: a real bolt ends after a few ticks
                    }
                };
                b.boltVertex = 0x20261027L;
                w.addWeatherEffect(b);
                batch.bolts.add(b);
            }
            default -> throw new IllegalArgumentException("specialScene: unknown kind '" + kind + "'");
        }
    }

    private static void place(Entity e, double x, double y, double z, float yaw) {
        e.setLocationAndAngles(x, y, z, yaw, 0.0F);
        e.ticksExisted = 100;
        Frozen.freezeEntity(e);
    }

    private static void add(WorldClient w, Batch batch, Entity e) {
        e.ticksExisted = 100;
        Frozen.freezeEntity(e);
        w.addEntityToWorld(nextId++, e);
        batch.entities.add(e);
    }

    private static EntityOtherPlayerMP player(WorldClient w, double x, double y, double z, float yaw) {
        ensureCape();
        EntityOtherPlayerMP p = new EntityOtherPlayerMP(w, "NR20Bot");
        place(p, x, y + p.yOffset, z, yaw);
        // the cape sways with the difference of the two position generations: none
        p.field_71091_bM = p.field_71094_bP = p.posX;
        p.field_71096_bN = p.field_71095_bQ = p.posY;
        p.field_71097_bO = p.field_71085_bR = p.posZ;
        return p;
    }

    /** Registers a fixed cape image under the cape location of the test player, so the download thread of the cape never matters. */
    private static void ensureCape() {
        if (capeLocation != null) {
            return;
        }
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                img.setRGB(x, y, 0xFF000000 | ((x * 4) << 16) | ((y * 8) << 8) | (((x + y) & 1) == 0 ? 0xC0 : 0x40));
            }
        }
        ResourceLocation loc = EntityOtherPlayerMP.getLocationCape("NR20Bot");
        ThreadDownloadImageData tex = new ThreadDownloadImageData("http://127.0.0.1:9/nr20-cape.png", null, null);
        tex.setBufferedImage(img);
        Minecraft.getMinecraft().getTextureManager().loadTexture(loc, tex);
        capeLocation = loc;
    }
}
