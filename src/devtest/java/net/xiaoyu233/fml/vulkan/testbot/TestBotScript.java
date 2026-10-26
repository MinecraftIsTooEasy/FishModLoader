package net.xiaoyu233.fml.vulkan.testbot;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import net.minecraft.BiomeGenBase;
import net.minecraft.ChunkPosition;
import net.minecraft.Entity;
import net.minecraft.EntityClientPlayerMP;
import net.minecraft.EntityList;
import net.minecraft.EnumGameType;
import net.minecraft.EnumParticle;
import net.minecraft.GuiControls;
import net.minecraft.GuiCreateWorld;
import net.minecraft.GuiIngameMenu;
import net.minecraft.GuiInventory;
import net.minecraft.GuiLanguage;
import net.minecraft.GuiMainMenu;
import net.minecraft.GuiMultiplayer;
import net.minecraft.GuiOptions;
import net.minecraft.GuiSelectWorld;
import net.minecraft.GuiVideoSettings;
import net.minecraft.Item;
import net.minecraft.ItemStack;
import net.minecraft.Minecraft;
import net.minecraft.ServerPlayer;
import net.minecraft.WorldServer;
import net.minecraft.WorldSettings;
import net.minecraft.WorldType;
import net.minecraft.server.MinecraftServer;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

/**
 * The linear test script of the Vulkan port, structured like the one of the user's Twilight Forest test bot: every step is wrapped (exceptions are
 * recorded, the next step still runs), runs on the bot thread and hands work to the client / server / render threads through {@link TestBot}.
 * <p>
 * Steps (ids are the prefixes for {@code -Ptestbot_only=title,world,traverse}): 00_startup (device + capability report), 01_title (loading screen
 * result, main menu, panorama animation, GUI screens), 02_enter (create / enter the world), 03_world (terrain, sky, time of day, weather, held
 * item, HUD, entities, particles, block museum, water / lava overlays), 04_traverse (the traversal tour with waypoint screenshots and perf CSV),
 * 99_final.
 */
final class TestBotScript {
    static final String WORLD = "vktest";
    static final long SEED = Long.getLong("fml.vulkan.testbot.seed", 20261027L);
    /** Multiplies every distance of the traversal route (0.25 for a quick smoke run, 1.0 = full tour). */
    static final double SCALE = Double.parseDouble(System.getProperty("fml.vulkan.testbot.scale", "1.0"));
    /** Seconds spent standing in one place at the end of the tour. */
    static final int IDLE_SECONDS = Integer.getInteger("fml.vulkan.testbot.idleSeconds", 60);

    final TestBot bot;
    final Minecraft mc;
    final Report rep;
    final PerfLog perf;
    final Set<String> skip = new HashSet<>();
    final Set<String> only = new HashSet<>();
    Report.Section sec;
    private int cameraWarns;
    private final List<String> wpRows = new ArrayList<>();
    private int wpCount;
    private double spawnX;
    private double spawnZ;

    TestBotScript(TestBot bot) {
        this.bot = bot;
        this.mc = bot.mc;
        this.rep = bot.report;
        this.perf = bot.perf;
        for (String s : System.getProperty("fml.vulkan.testbot.skip", "").split(",")) {
            if (!s.isBlank()) {
                skip.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        for (String s : System.getProperty("fml.vulkan.testbot.only", "").split(",")) {
            if (!s.isBlank()) {
                only.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    /** id "03_world" is selected by "03", "03_world" or "world"; 00 / 02 / 99 always run (02 only when a later world step is selected). */
    private boolean selected(String id) {
        if (id.startsWith("00_") || id.startsWith("99_") || only.isEmpty()) {
            return true;
        }
        String l = id.toLowerCase(Locale.ROOT);
        String name = l.substring(l.indexOf('_') + 1);
        for (String o : only) {
            if (l.startsWith(o) || name.equals(o)) {
                return true;
            }
        }
        return false;
    }

    private boolean needsWorld() {
        return selected("03_world") || selected("04_traverse");
    }

    private interface Body {
        void run(Report.Section s) throws Exception;
    }

    private boolean step(String id, String title, Body body) {
        if (bot.isAborted()) {
            rep.skipped(id, title + " (run aborted)");
            return false;
        }
        if (skip.contains(id.toLowerCase(Locale.ROOT)) || !selected(id) && !(id.startsWith("02_") && needsWorld())) {
            rep.skipped(id, title);
            return true;
        }
        sec = rep.begin(id, title);
        cameraWarns = 0;
        TestBot.log("step " + id + ": " + title);
        bot.status(title);
        perf.setLeg(id);
        try {
            body.run(sec);
        } catch (Throwable t) {
            sec.exception(title, t);
            t.printStackTrace();
        } finally {
            try {
                bot.afterFrame(() -> null);
            } catch (Exception ignored) {
                // run aborted / shut down
            }
            rep.end(sec);
        }
        return true;
    }

    void run() throws Exception {
        step("00_startup", "00 Startup: device, capabilities, log scan", this::stepStartup);
        step("01_title", "01 Title screen and menus", this::stepTitle);
        if (needsWorld()) {
            step("02_enter", "02 Create / enter the test world", this::enterWorld);
            if (bot.isAborted() || mc.thePlayer == null) {
                return;
            }
        }
        step("03_world", "03 World rendering: terrain, sky, time of day, hand, HUD, entities, particles", this::stepWorld);
        step("04_traverse", "04 Traversal tour: far flight in all directions, caves, overview, biomes, time of day, idle", this::stepTraverse);
        step("99_final", "99 Final log scan", s -> {
            s.markdown("Frame time per leg (every presented frame of the run, see the perf CSV for the raw data):\n\n" + perf.legTable());
            s.info("done", "all steps finished");
        });
    }

    // ================================================================== helpers

    MinecraftServer srv() {
        return MinecraftServer.getServer();
    }

    ServerPlayer sp() {
        return (ServerPlayer) srv().getConfigurationManager().playerEntityList.get(0);
    }

    WorldServer swOf(ServerPlayer p) {
        return srv().worldServerForDimension(p.dimension);
    }

    /** Server side: flying, invulnerable, no fall damage, no mobs / daylight cycle (screenshots must be repeatable). */
    void prepPlayer() throws Exception {
        bot.server(() -> {
            ServerPlayer p = sp();
            p.setGameType(EnumGameType.CREATIVE);
            p.capabilities.allowFlying = true;
            p.capabilities.isFlying = true;
            p.capabilities.disableDamage = true;
            p.sendPlayerAbilities();
            p.fallDistance = 0.0F;
            p.setHealth(p.getMaxHealth());
            for (WorldServer w : srv().worldServers) {
                if (w != null) {
                    try {
                        w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
                        w.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
                    } catch (Throwable ignored) {
                        // game rule API differs: not fatal
                    }
                }
            }
        });
    }

    /** Server side teleport (the player entity stands at feetY) and waits until the client agrees; then pins the exact pose on the client. */
    boolean place(double x, double feetY, double z, float yaw, float pitch) throws Exception {
        bot.server(() -> {
            ServerPlayer p = sp();
            p.fallDistance = 0.0F;
            p.playerNetServerHandler.setPlayerLocation(x, feetY, z, yaw, pitch);
        });
        boolean ok = bot.waitFor(() -> {
            EntityClientPlayerMP p = mc.thePlayer;
            return p != null && Math.abs(p.posX - x) < 0.6 && Math.abs(p.posZ - z) < 0.6 && Math.abs(p.boundingBox.minY - feetY) < 1.2;
        }, 100);
        if (!ok && sec != null && cameraWarns++ < 3) {
            EntityClientPlayerMP p = mc.thePlayer;
            sec.warn("teleport", "client player did not reach " + fmt(x) + "," + fmt(feetY) + "," + fmt(z) + " (is at "
                + (p == null ? "null" : fmt(p.posX) + "," + fmt(p.boundingBox.minY) + "," + fmt(p.posZ)) + ")");
        }
        bot.client(() -> {
            EntityClientPlayerMP p = mc.thePlayer;
            p.setPositionAndRotation(x, feetY, z, yaw, pitch);
            p.motionX = 0;
            p.motionY = 0;
            p.motionZ = 0;
        });
        return ok;
    }

    void look(float yaw, float pitch) throws Exception {
        bot.client(() -> setLook(yaw, pitch));
    }

    private void setLook(float yaw, float pitch) {
        EntityClientPlayerMP p = mc.thePlayer;
        p.rotationYaw = yaw;
        p.prevRotationYaw = yaw;
        p.rotationYawHead = yaw;
        p.prevRotationYawHead = yaw;
        p.rotationPitch = pitch;
        p.prevRotationPitch = pitch;
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    int pending() throws Exception {
        return bot.client(() -> perf.pendingUpdates(mc));
    }

    /**
     * Waits until the chunk renderer queue stayed empty for {@code quietTicks} consecutive ticks (after at least {@code minTicks}), or {@code maxTicks}
     * passed. Returns the seconds it took; the queue length at the end is reported when the limit was hit.
     */
    double settle(int minTicks, int quietTicks, int maxTicks) throws Exception {
        long start = System.currentTimeMillis();
        int quiet = 0;
        long lastTotal = bot.client(() -> perf.chunkUpdatesTotal());
        for (int i = 0; i < maxTicks; i++) {
            long[] st = bot.client(() -> new long[] {perf.chunkUpdatesTotal(), perf.pendingUpdates(mc)});
            long total = st[0];
            boolean idle = total == lastTotal || st[1] <= 0;
            lastTotal = total;
            if (i >= minTicks) {
                quiet = idle ? quiet + 1 : 0;
                if (quiet >= quietTicks) {
                    break;
                }
            }
        }
        return (System.currentTimeMillis() - start) / 1000.0;
    }

    /** Takes a numbered waypoint screenshot, records position / biome / queue length in the waypoint table, flags black frames. */
    String shot(Report.Section s, String name) throws Exception {
        String label = String.format("wp%03d_%s", ++wpCount, name);
        String path = bot.screenshot(s, label);
        if (path == null) {
            return null;
        }
        double[] pos = bot.client(() -> new double[] {mc.thePlayer.posX, mc.thePlayer.boundingBox.minY, mc.thePlayer.posZ, mc.thePlayer.rotationYaw,
            mc.thePlayer.rotationPitch});
        String biome = bot.client(() -> {
            try {
                return mc.theWorld.getBiomeGenForCoords((int) Math.floor(mc.thePlayer.posX), (int) Math.floor(mc.thePlayer.posZ)).biomeName;
            } catch (Throwable t) {
                return "?";
            }
        });
        int pend = pending();
        int tod = bot.client(() -> mc.theWorld.getAdjustedTimeOfDay());
        double luma = -1;
        try {
            luma = ImageStats.meanLuma(ImageStats.read(path));
        } catch (Throwable t) {
            s.warn("screenshot " + label, "cannot analyse: " + t);
        }
        if (luma >= 0 && luma < 1.0) {
            s.warn("screenshot " + label, "frame is black (mean luma " + fmt(luma) + ")");
        }
        wpRows.add(String.format(Locale.ROOT, "| %s | %s | %.0f / %.0f / %.0f | %.0f / %.0f | %s | %d | %d | %.0f |", label, perf.leg(), pos[0], pos[1], pos[2], pos[3], pos[4], biome,
            pend, tod, luma));
        return path;
    }

    String wpTable() {
        StringBuilder sb = new StringBuilder("| screenshot | leg | x / y / z | yaw / pitch | biome | queued rebuilds | client adjusted time | mean luma |\n|---|---|---|---|---|---|---|---|\n");
        for (String r : wpRows) {
            sb.append(r).append('\n');
        }
        return sb.toString();
    }

    // ================================================================== 00 startup

    void stepStartup(Report.Section s) throws Exception {
        for (String l : TestBot.startupLog()) {
            s.warn("startup log", l);
        }
        try {
            net.xiaoyu233.fml.vulkan.core.DeviceInfo d = RenderCore.deviceInfo();
            s.info("device", d.vendorName() + " " + d.deviceName() + ", driver " + d.driverInfo() + ", Vulkan api 0x" + Integer.toHexString(d.apiVersion()) + ", MoltenVK " + d.isMoltenVK()
                + ", wideLines " + d.wideLines() + ", fillModeNonSolid " + d.fillModeNonSolid() + ", anisotropy " + d.maxAnisotropy() + ", maxImageDimension2D " + d.maxImageDimension2D()
                + ", minUniformBufferOffsetAlignment " + d.minUniformBufferOffsetAlignment());
        } catch (Throwable t) {
            s.warn("device", "RenderCore.deviceInfo() failed: " + t);
        }
        s.info("window", Display.getWidth() + "x" + Display.getHeight() + " framebuffer pixels, framebuffer of the game " + bot.client(() -> mc.displayWidth + "x" + mc.displayHeight));
        // GL calls are only valid on the render thread
        String[] gl = bot.client(() -> new String[] {GL11.glGetString(GL11.GL_VENDOR), GL11.glGetString(GL11.GL_RENDERER), GL11.glGetString(GL11.GL_VERSION), GL11.glGetString(GL11.GL_EXTENSIONS)});
        s.info("GL strings", gl[0] + " | " + gl[1] + " | " + gl[2]);
        String ext = gl[3];
        s.check(ext != null && !ext.contains("GL_EXT_framebuffer_object") && !ext.contains("GL_ARB_shader_objects"), "GL 2.0 shader programs and FBOs are reported unsupported (mods disable themselves)",
            "extension string advertises shader objects / FBO: " + ext);
        s.info("options", "viewDistance index " + bot.client(() -> mc.gameSettings.getRenderDistance()) + ", fancy graphics " + bot.client(() -> mc.gameSettings.isFancyGraphicsEnabled())
            + ", testbot scale " + SCALE + ", seed " + SEED + ", server view distance property " + Integer.getInteger("fml.vulkan.testbot.viewDistance", 0)
            + ", vsync property " + System.getProperty("fml.vulkan.vsync") + ", presentMode " + System.getProperty("fml.vulkan.presentMode"));
    }

    // ================================================================== 01 title

    void stepTitle(Report.Section s) throws Exception {
        boolean menu = bot.waitFor(() -> mc.theWorld == null && mc.currentScreen instanceof GuiMainMenu, 3000);
        s.check(menu, "main menu reached", "main menu did not appear within 150 s");
        if (!menu) {
            return;
        }
        bot.client(() -> {
            mc.gameSettings.pauseOnLostFocus = false;
            mc.gameSettings.showDebugInfo = false;
        });
        bot.frames(30);
        String a = bot.screenshot(s, "title_a");
        bot.ticks(50);
        String b = bot.screenshot(s, "title_b");
        if (a != null && b != null) {
            BufferedImage ia = ImageStats.read(a);
            BufferedImage ib = ImageStats.read(b);
            // the panorama rotates (background), the logo / buttons are static: the background area (top strip + sides) must differ between the two shots
            double bg = ImageStats.meanAbsDiff(ia, ib, 0.0, 0.0, 1.0, 0.10);
            double ui = ImageStats.meanAbsDiff(ia, ib, 0.30, 0.45, 0.40, 0.25);
            s.info("panorama", "mean abs diff background strip " + fmt(bg) + ", button area " + fmt(ui) + " (0..255)");
            s.check(bg > 0.3, "panorama animates between two shots 2.5 s apart", "background strip identical (diff " + fmt(bg) + "): the panorama does not move");
            s.check(ImageStats.meanLuma(ia) > 20, "title frame is not black (luma " + fmt(ImageStats.meanLuma(ia)) + ")", "title frame is dark");
        }
        // GUI screens (each one a different mix of buttons, sliders, text fields, lists, tooltips)
        String[] names = {"options", "video", "controls", "language", "singleplayer", "createworld", "multiplayer"};
        for (String n : names) {
            try {
                bot.client(() -> {
                    GuiMainMenu parent = new GuiMainMenu();
                    switch (n) {
                        case "options" -> mc.displayGuiScreen(new GuiOptions(parent, mc.gameSettings));
                        case "video" -> mc.displayGuiScreen(new GuiVideoSettings(parent, mc.gameSettings));
                        case "controls" -> mc.displayGuiScreen(new GuiControls(parent, mc.gameSettings));
                        case "language" -> mc.displayGuiScreen(new GuiLanguage(parent, mc.gameSettings, mc.getLanguageManager()));
                        case "singleplayer" -> mc.displayGuiScreen(new GuiSelectWorld(parent));
                        case "createworld" -> mc.displayGuiScreen(new GuiCreateWorld(parent));
                        case "multiplayer" -> mc.displayGuiScreen(new GuiMultiplayer(parent));
                        default -> throw new IllegalStateException(n);
                    }
                });
                bot.frames(10);
                String p = bot.screenshot(s, "title_" + n);
                s.check(p != null, "screen " + n, "no screenshot");
            } catch (Throwable t) {
                s.exception("screen " + n, t);
            }
        }
        bot.client(() -> mc.displayGuiScreen(new GuiMainMenu()));
        bot.frames(10);
    }

    // ================================================================== 02 enter world

    void enterWorld(Report.Section s) throws Exception {
        boolean menu = bot.waitFor(() -> mc.theWorld == null && mc.currentScreen instanceof GuiMainMenu, 3000);
        s.check(menu, "main menu reached", "main menu did not appear within 150 s; trying to create the world anyway");
        bot.client(() -> {
            mc.gameSettings.pauseOnLostFocus = false;
            mc.gameSettings.showDebugInfo = false;
        });
        // the client thread is blocked inside launchIntegratedServer while the loading screen is drawn (it presents frames itself): capture it from here
        Thread loadingShots = new Thread(() -> {
            try {
                Thread.sleep(1500);
                RenderCore.requestCapture("loading_world_a", 0);
                Thread.sleep(3500);
                RenderCore.requestCapture("loading_world_b", 0);
            } catch (InterruptedException ignored) {
                // done
            }
        }, "VkTestBot-loadingshots");
        loadingShots.setDaemon(true);
        loadingShots.start();
        long t0 = System.currentTimeMillis();
        boolean keep = Boolean.getBoolean("fml.vulkan.testbot.keepWorld");
        bot.clientCall(() -> {
            WorldSettings ws = null;
            if (keep && mc.getSaveLoader().getWorldInfo(WORLD) != null) {
                // an existing world is loaded unchanged (settings null), like GuiSelectWorld does: saved chunks make repeated runs of one step fast
            } else {
                mc.getSaveLoader().deleteWorldDirectory(WORLD);
                ws = new WorldSettings(SEED, EnumGameType.CREATIVE, true, false, WorldType.DEFAULT, false).enableCommands();
            }
            mc.launchIntegratedServer(WORLD, WORLD, ws);
            return null;
        }, 900_000);
        boolean in = bot.waitFor(() -> mc.theWorld != null && mc.thePlayer != null && mc.currentScreen == null, 6000);
        double secs = (System.currentTimeMillis() - t0) / 1000.0;
        s.check(in, "world created and entered (default type, seed " + SEED + ")", "player never entered the world");
        if (!in) {
            bot.abort("could not create/enter the test world");
            return;
        }
        s.info("world generation", "spawn area ready after " + fmt(secs) + " s");
        s.screenshot("loading_world_a.png");
        s.screenshot("loading_world_b.png");
        prepPlayer();
        bot.ticks(20);
        double[] sp = bot.client(() -> new double[] {mc.thePlayer.posX, mc.thePlayer.boundingBox.minY, mc.thePlayer.posZ});
        spawnX = sp[0];
        spawnZ = sp[2];
        s.info("spawn", fmt(sp[0]) + " / " + fmt(sp[1]) + " / " + fmt(sp[2]));
        double settled = settle(40, 40, 1200);
        s.info("first chunks", "renderer queue empty after " + fmt(settled) + " s, queue now " + pending());
        rep.setWorldNote("default world '" + WORLD + "', seed " + SEED + ", creative flying player, doDaylightCycle / doMobSpawning off");
        bot.screenshot(s, "world_start");
    }

    // ================================================================== 03 world

    private static final int[][] MUSEUM_ROW = {
        // id, meta : stone, grass, dirt, cobblestone, planks, log, leaves, glass, ice, glowstone, workbench, furnace, chest, cactus (needs sand), torch, ladder, fence
        {1, 0}, {2, 0}, {3, 0}, {4, 0}, {5, 0}, {17, 0}, {18, 0}, {20, 0}, {79, 0}, {89, 0}, {58, 0}, {61, 2}, {54, 2}, {85, 0}, {50, 5}, {65, 2}
    };

    void stepWorld(Report.Section s) throws Exception {
        double[] p0 = bot.client(() -> new double[] {mc.thePlayer.posX, mc.thePlayer.boundingBox.minY, mc.thePlayer.posZ});
        double gx = p0[0];
        double gz = p0[2];
        int ground = bot.client(() -> mc.theWorld.getHeightValue((int) Math.floor(gx), (int) Math.floor(gz)));
        s.info("ground", "terrain top at spawn column y=" + ground);

        // ---- natural terrain around the spawn: four directions + sky + ground, flying 6 blocks above the surface
        place(gx, ground + 6, gz, 0.0F, 8.0F);
        settle(40, 40, 600);
        for (int i = 0; i < 4; i++) {
            look(i * 90.0F, 8.0F);
            bot.ticks(8);
            s.check(shot(s, "spawn_yaw" + (i * 90)) != null, "terrain view yaw " + i * 90, "no screenshot");
        }
        look(0.0F, -75.0F);
        bot.ticks(8);
        shot(s, "spawn_up_sky_clouds");
        look(0.0F, 85.0F);
        bot.ticks(8);
        shot(s, "spawn_down_ground");

        // ---- time of day on the MITE clock (adjusted: sunrise 5000, noon 12000, sunset 19000, midnight 0): sun / sunset / moon + stars / sunrise
        // (the sun rises in the east: yaw -90, sets in the west: yaw 90)
        int[][] times = {{12000, 0, -60}, {19000, 90, 5}, {0, 0, -50}, {5000, -90, 8}};
        String[] tnames = {"noon", "sunset", "midnight", "sunrise"};
        for (int i = 0; i < times.length; i++) {
            setTimeOfDay(times[i][0]);
            look(times[i][1], times[i][2]);
            bot.ticks(12);
            shot(s, "time_" + tnames[i]);
        }
        setTimeOfDay(12000);

        // ---- clouds from above + fog: climb to y=170 and look at the horizon and down onto the cloud layer
        place(gx, 170, gz, 0.0F, 25.0F);
        settle(30, 20, 400);
        shot(s, "high_horizon_fog");
        look(0.0F, 60.0F);
        bot.ticks(8);
        shot(s, "high_down_clouds");

        // ---- block museum on a floating platform
        buildMuseum(s, gx, gz);
        double cx = Math.floor(gx) + 0.5;
        double cz = Math.floor(gz) + 0.5;
        place(cx, 181.0, cz - 8.0, 0.0F, 12.0F);
        settle(20, 20, 400);
        shot(s, "museum_front");
        look(0.0F, 55.0F);
        bot.ticks(8);
        shot(s, "museum_down");
        look(40.0F, 8.0F);
        bot.ticks(8);
        shot(s, "museum_angle");

        // ---- held item and first person hand
        giveHotbar();
        look(0.0F, 12.0F);
        for (int slot = 0; slot < 5; slot++) {
            final int sl = slot;
            bot.client(() -> mc.thePlayer.inventory.currentItem = sl);
            bot.server(() -> sp().inventory.currentItem = sl);
            bot.ticks(18);
            shot(s, "hand_slot" + slot);
        }
        bot.client(() -> mc.thePlayer.swingArm());
        bot.ticks(3);
        shot(s, "hand_swing");
        bot.client(() -> mc.gameSettings.thirdPersonView = 1);
        bot.ticks(10);
        shot(s, "view_third_back");
        bot.client(() -> mc.gameSettings.thirdPersonView = 2);
        bot.ticks(10);
        shot(s, "view_third_front");
        bot.client(() -> mc.gameSettings.thirdPersonView = 0);

        // ---- entities (animated models, display lists of model parts, shadows)
        List<Integer> ids = spawnMobs(cx, cz);
        s.info("entities", ids.size() + " mobs spawned on the platform");
        bot.ticks(30);
        look(0.0F, 10.0F);
        bot.ticks(5);
        shot(s, "entities_front");
        look(0.0F, 25.0F);
        bot.client(() -> mc.gameSettings.thirdPersonView = 0);
        bot.ticks(40);
        shot(s, "entities_later");

        // ---- particles
        EnumParticle[] ps = {EnumParticle.flame, EnumParticle.smoke, EnumParticle.heart, EnumParticle.crit, EnumParticle.portal_nether, EnumParticle.reddust, EnumParticle.explode,
            EnumParticle.splash, EnumParticle.happyVillager, EnumParticle.largesmoke, EnumParticle.enchantmenttable};
        bot.client(() -> {
            for (EnumParticle ep : ps) {
                for (int i = 0; i < 12; i++) {
                    mc.theWorld.spawnParticle(ep, cx + (i % 4) - 1.5, 182.5 + (i / 4) * 0.4, cz - 3.0 + (i % 3), 0.0, 0.02, 0.0);
                }
            }
        });
        bot.ticks(4);
        shot(s, "particles");
        bot.server(() -> {
            WorldServer w = swOf(sp());
            for (Integer id : ids) {
                Entity e = w.getEntityByID(id);
                if (e != null) {
                    e.setDead();
                }
            }
        });

        // ---- weather: rain + a lightning bolt
        bot.client(() -> {
            mc.theWorld.setRainStrength(1.0F);
        });
        look(0.0F, 5.0F);
        bot.ticks(20);
        shot(s, "weather_rain");
        bot.server(() -> {
            WorldServer w = swOf(sp());
            w.addWeatherEffect(new net.minecraft.EntityLightningBolt(w, cx, 181, cz + 10));
        });
        bot.ticks(2);
        shot(s, "weather_lightning");
        bot.client(() -> {
            mc.theWorld.setRainStrength(0.0F);
        });

        // ---- water / lava overlays (underwater fog, tint, fire in first person)
        bot.server(() -> {
            WorldServer w = swOf(sp());
            for (int x = -2; x <= 2; x++) {
                for (int y = 0; y <= 3; y++) {
                    for (int z = -2; z <= 2; z++) {
                        w.setBlock((int) cx + 12 + x, 185 + y, (int) cz + z, 9, 0, 2);
                    }
                }
            }
        });
        place(cx + 12, 186, cz, 0.0F, 10.0F);
        bot.ticks(30);
        shot(s, "overlay_underwater");
        bot.server(() -> {
            WorldServer w = swOf(sp());
            for (int x = -2; x <= 2; x++) {
                for (int y = 0; y <= 3; y++) {
                    for (int z = -2; z <= 2; z++) {
                        w.setBlock((int) cx + 12 + x, 185 + y, (int) cz + z, 0, 0, 2);
                        w.setBlock((int) cx + 22 + x, 185 + y, (int) cz + z, 11, 0, 2);
                    }
                }
            }
        });
        place(cx + 22, 186, cz, 0.0F, 10.0F);
        bot.ticks(20);
        shot(s, "overlay_lava");
        bot.server(() -> {
            WorldServer w = swOf(sp());
            for (int x = -2; x <= 2; x++) {
                for (int y = 0; y <= 3; y++) {
                    for (int z = -2; z <= 2; z++) {
                        w.setBlock((int) cx + 22 + x, 185 + y, (int) cz + z, 0, 0, 2);
                    }
                }
            }
        });
        prepPlayer();
        place(cx, 181.0, cz - 8.0, 0.0F, 12.0F);
        bot.ticks(10);

        // ---- HUD in survival (health, food, armor, xp, hotbar), chat, debug text, inventory, pause menu
        bot.server(() -> {
            ServerPlayer p = sp();
            p.setGameType(EnumGameType.SURVIVAL);
            p.capabilities.disableDamage = true;
            p.addExperience(300);
        });
        bot.ticks(15);
        shot(s, "hud_survival");
        bot.client(() -> mc.ingameGUI.getChatGUI().printChatMessage("<VkTester> hello from the Vulkan backend, text with §aco§blo§cur§r codes"));
        bot.client(() -> mc.gameSettings.showDebugInfo = true);
        bot.ticks(10);
        shot(s, "hud_debug_f3");
        bot.client(() -> mc.gameSettings.showDebugInfo = false);
        bot.client(() -> mc.displayGuiScreen(new GuiInventory(mc.thePlayer)));
        bot.frames(10);
        shot(s, "gui_inventory");
        bot.client(() -> mc.displayGuiScreen(new GuiIngameMenu()));
        bot.frames(10);
        shot(s, "gui_pause_menu");
        bot.client(() -> mc.displayGuiScreen(null));
        bot.frames(5);
        prepPlayer();
        place(cx, 181.0, cz - 8.0, 0.0F, 12.0F);

        s.markdown("Waypoint screenshots:\n\n" + wpTable());
    }

    /**
     * Sets the ADJUSTED time of day (MITE clock: sunrise 5000, noon 12000, sunset 19000, midnight 0 / 24000) on the server (all clients are told at once) and
     * waits until the client world shows it; reports how many ticks that took. World time keeps running (MITE ignores doDaylightCycle), the drift during a
     * screenshot is a few dozen ticks.
     */
    void setTimeOfDay(long t) throws Exception {
        long t0 = bot.tickCount();
        bot.server(() -> {
            for (WorldServer w : srv().worldServers) {
                if (w != null && w.provider.dimensionId == 0) {
                    w.setTotalWorldTime(w.getTotalWorldTimeAtStartOfToday() + t, true);
                }
            }
        });
        boolean ok = bot.waitFor(() -> {
            int d = Math.abs(mc.theWorld.getAdjustedTimeOfDay() - (int) t);
            return Math.min(d, 24000 - d) < 300;
        }, 200);
        long took = bot.tickCount() - t0;
        if (sec != null) {
            if (!ok) {
                sec.warn("time of day " + t, "the client world did not show the new time within 200 ticks (client adjusted time of day " + bot.client(() -> mc.theWorld.getAdjustedTimeOfDay()) + ")");
            } else if (took > 25) {
                sec.warn("time of day " + t, "the client needed " + took + " ticks to show the new time");
            } else {
                sec.ok("time of day " + t + " reached the client in " + took + " ticks");
            }
        }
        bot.ticks(3);
    }

    private void buildMuseum(Report.Section s, double gx, double gz) throws Exception {
        int cx = (int) Math.floor(gx);
        int cz = (int) Math.floor(gz);
        bot.server(() -> {
            WorldServer w = swOf(sp());
            // platform 49 x 25 at y=180 (stone), glass fence ring
            for (int x = -24; x <= 24; x++) {
                for (int z = -12; z <= 12; z++) {
                    w.setBlock(cx + x, 180, cz + z, 1, 0, 2);
                    for (int y = 181; y <= 190; y++) {
                        w.setBlock(cx + x, y, cz + z, 0, 0, 2);
                    }
                }
            }
            // row of blocks (z = cz), 2 apart
            for (int i = 0; i < MUSEUM_ROW.length; i++) {
                int x = cx - 15 + i * 2;
                if (MUSEUM_ROW[i][0] == 50) {
                    w.setBlock(x, 181, cz, 1, 0, 2);
                    w.setBlock(x, 182, cz, 50, 5, 2);
                } else if (MUSEUM_ROW[i][0] == 65) {
                    w.setBlock(x, 181, cz, 1, 0, 2);
                    w.setBlock(x, 182, cz, 1, 0, 2);
                    w.setBlock(x, 181, cz - 1, 65, 2, 2);
                    w.setBlock(x, 182, cz - 1, 65, 2, 2);
                } else {
                    w.setBlock(x, 181, cz, MUSEUM_ROW[i][0], MUSEUM_ROW[i][1], 2);
                }
            }
            // plants on grass: dandelion, rose, tall grass, sapling, cactus on sand, wheat-like crops
            int px = cx - 15;
            int[][] plants = {{37, 0}, {38, 0}, {31, 1}, {6, 0}, {81, 0}, {83, 0}};
            for (int i = 0; i < plants.length; i++) {
                int x = px + i * 2;
                boolean cactus = plants[i][0] == 81 || plants[i][0] == 83;
                w.setBlock(x, 180, cz + 3, cactus ? 12 : 2, 0, 2);
                w.setBlock(x, 181, cz + 3, plants[i][0], plants[i][1], 2);
            }
            // water pool (stone rim, 3x3 source blocks) and lava pool
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean rim = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                    w.setBlock(cx + 8 + dx, 181, cz + 6 + dz, rim ? 1 : 9, 0, 2);
                    w.setBlock(cx + 14 + dx, 181, cz + 6 + dz, rim ? 1 : 11, 0, 2);
                }
            }
            // glass pane wall + stained glass-like wool colours
            for (int i = 0; i < 16; i++) {
                w.setBlock(cx - 8 + i, 181, cz + 9, 35, i, 2);
                w.setBlock(cx - 8 + i, 182, cz + 9, 102, 0, 2);
            }
            // a tree-like column of logs with leaves
            for (int y = 181; y <= 184; y++) {
                w.setBlock(cx + 18, y, cz + 2, 17, 0, 2);
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        if (Math.abs(dx) + Math.abs(dz) < 4) {
                            w.setBlock(cx + 18 + dx, 184 + dy, cz + 2 + dz, 18, 0, 2);
                        }
                    }
                }
            }
        });
        s.info("museum", "platform at y=180 around " + cx + "," + cz + " with " + MUSEUM_ROW.length + " block types, plants, pools, wool/pane wall, tree");
        bot.ticks(10);
    }

    private void giveHotbar() throws Exception {
        Item[] items = {Item.swordIron, Item.pickaxeIron, null, Item.appleRed, null};
        int[] blockIds = {0, 0, 50, 0, 1};
        bot.server(() -> {
            ServerPlayer p = sp();
            for (int i = 0; i < 5; i++) {
                ItemStack st = items[i] != null ? new ItemStack(items[i], 1) : new ItemStack(blockIds[i], 64, 0);
                p.inventory.mainInventory[i] = st;
            }
            p.inventory.currentItem = 0;
            p.inventoryContainer.detectAndSendChanges();
        });
        bot.client(() -> {
            for (int i = 0; i < 5; i++) {
                ItemStack st = items[i] != null ? new ItemStack(items[i], 1) : new ItemStack(blockIds[i], 64, 0);
                mc.thePlayer.inventory.mainInventory[i] = st;
            }
            mc.thePlayer.inventory.currentItem = 0;
        });
        bot.ticks(5);
    }

    private List<Integer> spawnMobs(double cx, double cz) throws Exception {
        String[] names = {"Pig", "Cow", "Sheep", "Chicken", "Zombie", "Skeleton", "Creeper", "Spider", "Villager", "Wolf", "Horse", "Slime"};
        return bot.server(() -> {
            WorldServer w = swOf(sp());
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < names.length; i++) {
                Entity e = EntityList.createEntityByName(names[i], w);
                if (e == null) {
                    continue;
                }
                e.setLocationAndAngles(cx - 11 + i * 2.0, 181.0, cz + 4.0 + (i % 2) * 2.0, 180.0F, 0.0F);
                w.spawnEntityInWorld(e);
                ids.add(e.entityId);
            }
            return ids;
        });
    }

    // ================================================================== flight driver (client side, like real flying: the integrated server follows the player)

    /**
     * Flies the (creative, flying) client player towards a target at a constant horizontal speed with the camera looking along the way. The altitude follows
     * the terrain (highest heightmap value ahead + clearance) or stays at a fixed height. Runs at the end of every client tick; the integrated server
     * accepts the movement like any client movement and keeps loading chunks around the player (a server side setPlayerLocation every tick would reset the
     * server's hasMoved flag and stop the chunk watcher, so teleports are used only for single jumps).
     */
    final class Flight {
        final double tx;
        final double tz;
        final double speed;
        final double clearance;
        final double fixedY;
        final float pitch;
        volatile boolean done;
        volatile double x;
        volatile double z;
        volatile String note = "";
        int ticks;
        double lift;
        double progressMark;
        int stuckEvents;
        double lastDist = Double.MAX_VALUE;

        Flight(double tx, double tz, double speed, double clearance, double fixedY, float pitch) {
            this.tx = tx;
            this.tz = tz;
            this.speed = speed;
            this.clearance = clearance;
            this.fixedY = fixedY;
            this.pitch = pitch;
        }

        void tick() {
            EntityClientPlayerMP p = mc.thePlayer;
            if (p == null || done) {
                return;
            }
            ticks++;
            x = p.posX;
            z = p.posZ;
            double dx = tx - p.posX;
            double dz = tz - p.posZ;
            double dist = Math.hypot(dx, dz);
            if (dist <= Math.max(speed * 1.5, 1.0)) {
                p.motionX = 0;
                p.motionY = 0;
                p.motionZ = 0;
                done = true;
                return;
            }
            double ux = dx / dist;
            double uz = dz / dist;
            double feet = p.boundingBox.minY;
            double want;
            if (Double.isNaN(fixedY)) {
                int top = 0;
                double[] ahead = {0, 6, 12, 20, 32, 48, 64};
                for (double a : ahead) {
                    for (int side = -1; side <= 1; side++) {
                        int sx = (int) Math.floor(p.posX + ux * a - uz * side * 3);
                        int sz = (int) Math.floor(p.posZ + uz * a + ux * side * 3);
                        top = Math.max(top, mc.theWorld.getHeightValue(sx, sz));
                    }
                }
                want = Math.max(top + clearance + lift, 75 + lift);
            } else {
                want = fixedY;
            }
            double vy = Math.max(-0.7, Math.min(1.3, (want - feet) * 0.3));
            p.motionX = ux * speed;
            p.motionZ = uz * speed;
            p.motionY = vy;
            p.capabilities.isFlying = true;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float cur = p.rotationYaw;
            float diff = ((yaw - cur + 540.0F) % 360.0F) - 180.0F;
            float next = cur + Math.max(-12.0F, Math.min(12.0F, diff));
            p.rotationYaw = next;
            p.prevRotationYaw = next;
            p.rotationYawHead = next;
            p.prevRotationYawHead = next;
            p.rotationPitch = pitch;
            p.prevRotationPitch = pitch;
            // stuck detection: far less progress than expected over 40 ticks (flew into a hill / tree): fly higher
            if (ticks % 40 == 0) {
                if (lastDist - dist < speed * 40 * 0.25) {
                    lift += 10;
                    stuckEvents++;
                    note = "stuck " + stuckEvents + "x (lift " + (int) lift + ")";
                }
                lastDist = dist;
            }
        }
    }

    /** Runs a flight to completion (or until it takes far too long); returns false when it had to be abandoned. */
    boolean fly(Report.Section s, String leg, double tx, double tz, double speed, double clearance, double fixedY, float pitch, int shotEveryBlocks) throws Exception {
        perf.setLeg(leg);
        bot.status("flight " + leg);
        Flight f = new Flight(tx, tz, speed, clearance, fixedY, pitch);
        double[] start = bot.client(() -> new double[] {mc.thePlayer.posX, mc.thePlayer.posZ});
        double total = Math.hypot(tx - start[0], tz - start[1]);
        long maxTicks = (long) (total / speed * 1.8) + 400;
        bot.setClientHook(f::tick);
        double nextShot = shotEveryBlocks > 0 ? shotEveryBlocks : Double.MAX_VALUE;
        long t0 = bot.tickCount();
        boolean ok = true;
        try {
            while (!f.done) {
                bot.ticks(4);
                if (bot.tickCount() - t0 > maxTicks) {
                    ok = false;
                    s.warn("flight " + leg, "timeout after " + (bot.tickCount() - t0) + " ticks, " + fmt(Math.hypot(tx - f.x, tz - f.z)) + " blocks short of the target (" + f.note + ")");
                    break;
                }
                double travelled = Math.hypot(f.x - start[0], f.z - start[1]);
                if (travelled >= nextShot && !f.done) {
                    nextShot += shotEveryBlocks;
                    shot(s, leg + "_" + (int) travelled);
                }
            }
        } finally {
            bot.setClientHook(null);
        }
        bot.client(() -> {
            mc.thePlayer.motionX = 0;
            mc.thePlayer.motionY = 0;
            mc.thePlayer.motionZ = 0;
        });
        if (f.stuckEvents > 0) {
            s.info("flight " + leg, f.note);
        }
        return ok;
    }

    /** Four stills 90 degrees apart (each preceded by a short wait), then one slow continuous 360 degree turn without screenshots. */
    void sweep(Report.Section s, String name, float pitch, boolean continuous) throws Exception {
        perf.setLeg(name);
        float base = bot.client(() -> mc.thePlayer.rotationYaw);
        for (int i = 0; i < 4; i++) {
            look(base + i * 90.0F, pitch);
            bot.ticks(10);
            shot(s, name + "_" + i * 90);
        }
        if (continuous) {
            perf.setLeg(name + "_turn");
            for (int i = 0; i < 90; i++) {
                look(base + i * 4.0F, pitch);
                bot.ticks(1);
            }
        }
    }

    // ================================================================== 04 traverse

    void stepTraverse(Report.Section s) throws Exception {
        wpRows.clear();
        wpCount = 0;
        prepPlayer();
        setTimeOfDay(12000);
        double[] p0 = bot.client(() -> new double[] {mc.thePlayer.posX, mc.thePlayer.posZ});
        double ox = p0[0];
        double oz = p0[1];
        s.info("origin", fmt(ox) + " / " + fmt(oz) + ", route scale " + SCALE);
        boolean cave = !Boolean.getBoolean("fml.vulkan.testbot.noCave");

        // ---- 1. spawn: back on the ground level, sweep
        int top = bot.client(() -> mc.theWorld.getHeightValue((int) Math.floor(ox), (int) Math.floor(oz)));
        place(ox, top + 14, oz, 0.0F, 10.0F);
        settle(40, 40, 600);
        sweep(s, "t00_spawn", 10.0F, true);

        // ---- 2. square ring far out in all four directions: N, E, S, W and back (new terrain all the way)
        double r = 700 * SCALE;
        double speed = 1.0;
        double[][] ring = {{0, -r, 0}, {r, -r, 1}, {r, r, 2}, {-r, r, 3}, {-r, -r, 4}, {0, -r, 5}};
        String[] ringNames = {"t10_north", "t11_east", "t12_south", "t13_west", "t14_north_again", "t15_north_mid"};
        for (int i = 0; i < ring.length; i++) {
            double tx = ox + ring[i][0];
            double tz = oz + ring[i][1];
            double len = i == 0 ? r : i == 1 ? r : i == 4 ? r * 2 : i == 5 ? r : r * 2;
            fly(s, ringNames[i], tx, tz, speed, 12, Double.NaN, 12.0F, (int) Math.max(150, len / 4));
            settle(40, 60, 900);
            if (i <= 3) {
                sweep(s, ringNames[i] + "_end", 10.0F, false);
            }
        }

        // ---- 3. underground: natural cave if one is loaded nearby, else a carved tunnel with torches; then fly through the tunnel
        if (cave) {
            legCave(s);
        }

        // ---- 4. overview from y=200 and a slow flight at altitude
        double[] here = bot.client(() -> new double[] {mc.thePlayer.posX, mc.thePlayer.posZ});
        place(here[0], 200, here[1], 0.0F, 15.0F);
        settle(40, 60, 900);
        sweep(s, "t30_overview", 15.0F, true);
        fly(s, "t31_overview_flight", here[0] + 250 * SCALE, here[1] + 250 * SCALE, 1.0, 0, 200, 15.0F, (int) Math.max(100, 120 * SCALE));
        settle(40, 60, 900);

        // ---- 5. biomes: ocean (fly across it), desert, snow (with rain = snowfall)
        legBiome(s, "t40_ocean", new BiomeGenBase[] {BiomeGenBase.ocean}, 2500, 150, 8, true);
        legBiome(s, "t41_desert", new BiomeGenBase[] {BiomeGenBase.desert, BiomeGenBase.desertHills}, 2500, 100, 14, false);
        legBiome(s, "t42_snow", new BiomeGenBase[] {BiomeGenBase.icePlains, BiomeGenBase.iceMountains, BiomeGenBase.taiga}, 2500, 100, 14, false);
        bot.client(() -> mc.theWorld.setRainStrength(1.0F));
        bot.ticks(40);
        shot(s, "t42_snow_precipitation");
        bot.client(() -> mc.theWorld.setRainStrength(0.0F));

        // ---- 6. back to the spawn area (single long jump: everything around has to be loaded and meshed again), staleness test, time of day, idle
        int top2 = jumpTo(s, "t50_return_spawn", ox, oz, 14);
        settle(40, 60, 1200);
        shot(s, "t50_spawn_after_return");
        staleChunkTest(s, ox, oz);
        legTimeOfDay(s, ox, oz);
        legIdle(s, ox, oz);
        s.markdown("Waypoints of the tour:\n\n" + wpTable());
        s.markdown("Memory trend over the run (heap floor = lowest used heap per second, i.e. after the GCs):\n\n" + perf.memorySummary());
        s.info("end", "tour finished, waypoint screenshots: " + wpCount);
    }

    /** Teleports the player to (x, ground+clearance, z), looking at the horizon; the target column is generated on arrival (single long jump). Returns the ground height. */
    int jumpTo(Report.Section s, String leg, double x, double z, int clearance) throws Exception {
        perf.setLeg(leg);
        place(x, 210, z, 0.0F, 10.0F);
        settle(30, 40, 1200);
        int top = bot.client(() -> mc.theWorld.getHeightValue((int) Math.floor(x), (int) Math.floor(z)));
        if (top <= 0) {
            bot.ticks(40);
            top = bot.client(() -> mc.theWorld.getHeightValue((int) Math.floor(x), (int) Math.floor(z)));
        }
        place(x, Math.max(top, 63) + clearance, z, 0.0F, 10.0F);
        return top;
    }

    private void legBiome(Report.Section s, String leg, BiomeGenBase[] biomes, int range, int flyBlocks, int clearance, boolean water) throws Exception {
        perf.setLeg(leg);
        double[] here = bot.client(() -> new double[] {mc.thePlayer.posX, mc.thePlayer.posZ});
        double[] found = bot.server(() -> {
            WorldServer w = swOf(sp());
            ChunkPosition cp = w.getWorldChunkManager().findBiomePosition((int) here[0], (int) here[1], range, java.util.Arrays.asList(biomes), new Random(SEED));
            return cp == null ? null : new double[] {cp.x, cp.z};
        });
        if (found == null) {
            s.warn(leg, "no " + biomes[0].biomeName + " within " + range + " blocks of " + fmt(here[0]) + "," + fmt(here[1]) + ": leg skipped");
            return;
        }
        s.info(leg, biomes[0].biomeName + " found at " + fmt(found[0]) + "," + fmt(found[1]) + " (" + fmt(Math.hypot(found[0] - here[0], found[1] - here[1])) + " blocks away)");
        int top = jumpTo(s, leg, found[0], found[1], clearance);
        settle(40, 60, 1200);
        shot(s, leg + "_arrive");
        look(bot.client(() -> mc.thePlayer.rotationYaw), 55.0F);
        bot.ticks(10);
        shot(s, leg + "_ground");
        if (water) {
            // fly across the water, then dip under the surface for the underwater fog / tint
            fly(s, leg + "_flight", found[0] + flyBlocks, found[1], 0.8, 0, 72, 12.0F, 50);
            settle(20, 40, 600);
            int floor = bot.client(() -> {
                int fy = 40;
                for (int y = 62; y > 30; y--) {
                    int id = mc.theWorld.getBlockId((int) Math.floor(found[0] + flyBlocks), y, (int) Math.floor(found[1]));
                    if (id != 0 && id != 8 && id != 9) {
                        fy = y;
                        break;
                    }
                }
                return fy;
            });
            place(found[0] + flyBlocks, Math.min(floor + 3, 59), found[1], 0.0F, 5.0F);
            bot.ticks(40);
            shot(s, leg + "_underwater");
            place(found[0] + flyBlocks, 75, found[1], 0.0F, 12.0F);
        } else {
            fly(s, leg + "_flight", found[0] + flyBlocks, found[1] + flyBlocks / 2.0, 0.8, clearance, Double.NaN, 12.0F, 50);
            settle(20, 40, 600);
            sweep(s, leg + "_sweep", 8.0F, false);
        }
    }

    private void legCave(Report.Section s) throws Exception {
        perf.setLeg("t20_cave");
        double[] here = bot.client(() -> new double[] {mc.thePlayer.posX, mc.thePlayer.posZ});
        // go down to y ~ 40 first so the surrounding chunks of the underground are loaded for sure
        place(here[0], 140, here[1], 0.0F, 10.0F);
        settle(30, 40, 600);
        int[] c = bot.server(() -> findCave(swOf(sp()), (int) Math.floor(here[0]), (int) Math.floor(here[1])));
        if (c != null) {
            s.info("cave", "natural cave found at " + c[0] + "," + c[1] + "," + c[2] + " (" + c[3] + " air blocks in the probe box)");
            place(c[0] + 0.5, c[1], c[2] + 0.5, 0.0F, 5.0F);
            settle(30, 40, 600);
            sweep(s, "t20_cave", 5.0F, false);
        } else {
            s.warn("cave", "no natural cave within 48 blocks of " + fmt(here[0]) + "," + fmt(here[1]) + " (y 14..40): only the carved tunnel is tested");
        }
        // carved tunnel: 5 wide, 4 high, 90 long along +x at y=22 with a torch every 10 blocks
        int tx0 = (int) Math.floor(here[0]) - 10;
        int tz0 = (int) Math.floor(here[1]);
        bot.server(() -> {
            WorldServer w = swOf(sp());
            for (int x = 0; x < 90; x++) {
                for (int dz = -2; dz <= 2; dz++) {
                    w.setBlock(tx0 + x, 21, tz0 + dz, 1, 0, 2);
                    for (int dy = 0; dy < 4; dy++) {
                        w.setBlock(tx0 + x, 22 + dy, tz0 + dz, 0, 0, 2);
                    }
                    w.setBlock(tx0 + x, 26, tz0 + dz, 1, 0, 2);
                }
                if (x % 10 == 5) {
                    w.setBlock(tx0 + x, 22, tz0 - 2, 50, 5, 2);
                }
            }
        });
        s.info("tunnel", "carved 90x5x4 tunnel at x " + tx0 + ".." + (tx0 + 89) + ", y 22..25, z " + tz0);
        place(tx0 + 2.5, 22, tz0 + 0.5, -90.0F, 0.0F);
        settle(30, 40, 600);
        shot(s, "t21_tunnel_start");
        fly(s, "t21_tunnel_flight", tx0 + 85, tz0 + 0.5, 0.4, 0, 22.2, 0.0F, 30);
        bot.ticks(10);
        shot(s, "t21_tunnel_end");
        sweep(s, "t21_tunnel_end_sweep", 0.0F, false);
    }

    /** First loaded spot below y=40 (y>=14) with open air around it that cannot see the sky; null when there is none. */
    private int[] findCave(WorldServer w, int px, int pz) {
        int[] best = null;
        for (int dx = -48; dx <= 48; dx += 3) {
            for (int dz = -48; dz <= 48; dz += 3) {
                int x = px + dx;
                int z = pz + dz;
                if (!w.blockExists(x, 30, z)) {
                    continue;
                }
                for (int y = 14; y <= 40; y++) {
                    if (w.getBlockId(x, y, z) != 0 || w.getBlockId(x, y + 1, z) != 0 || w.getBlockId(x, y - 1, z) == 0 || w.canBlockSeeTheSky(x, y + 1, z)) {
                        continue;
                    }
                    int air = 0;
                    for (int ax = -2; ax <= 2; ax++) {
                        for (int az = -2; az <= 2; az++) {
                            for (int ay = 0; ay <= 2; ay++) {
                                if (w.getBlockId(x + ax, y + ay, z + az) == 0) {
                                    air++;
                                }
                            }
                        }
                    }
                    if (air >= 30 && (best == null || air > best[3])) {
                        best = new int[] {x, y, z, air};
                    }
                }
            }
        }
        return best;
    }

    /**
     * Staleness / holes test: a 5x5x5 glowstone cube appears in front of the player, then disappears; the cube must show up in the frame while it exists and
     * the frame after removal must match the frame before (meshes rebuilt, nothing stale).
     */
    private void staleChunkTest(Report.Section s, double ox, double oz) throws Exception {
        perf.setLeg("t60_stale");
        int top = bot.client(() -> mc.theWorld.getHeightValue((int) Math.floor(ox), (int) Math.floor(oz)));
        double y = top + 14;
        place(ox, y, oz, 0.0F, 25.0F);
        settle(30, 40, 600);
        int cx = (int) Math.floor(ox) - 2;
        int cz = (int) Math.floor(oz) + 10;
        int cy = (int) y - 3;
        bot.ticks(20);
        String a = bot.screenshot(s, "t60_stale_a_before");
        bot.server(() -> {
            WorldServer w = swOf(sp());
            for (int x = 0; x < 5; x++) {
                for (int yy = 0; yy < 5; yy++) {
                    for (int z = 0; z < 5; z++) {
                        w.setBlock(cx + x, cy + yy, cz + z, 89, 0, 2);
                    }
                }
            }
        });
        settle(10, 30, 600);
        bot.ticks(10);
        String b = bot.screenshot(s, "t60_stale_b_cube");
        bot.server(() -> {
            WorldServer w = swOf(sp());
            for (int x = 0; x < 5; x++) {
                for (int yy = 0; yy < 5; yy++) {
                    for (int z = 0; z < 5; z++) {
                        w.setBlock(cx + x, cy + yy, cz + z, 0, 0, 2);
                    }
                }
            }
        });
        settle(10, 30, 600);
        bot.ticks(10);
        String c = bot.screenshot(s, "t60_stale_c_after");
        if (a != null && b != null && c != null) {
            BufferedImage ia = ImageStats.read(a);
            BufferedImage ib = ImageStats.read(b);
            BufferedImage ic = ImageStats.read(c);
            double ab = ImageStats.meanAbsDiff(ia, ib, 0.3, 0.2, 0.4, 0.6);
            double ac = ImageStats.meanAbsDiff(ia, ic, 0.3, 0.2, 0.4, 0.6);
            s.info("stale chunk test", "before vs cube " + fmt(ab) + ", before vs after removal " + fmt(ac) + " (mean abs diff 0..255 in the centre box)");
            s.check(ab > 2.0, "the cube is visible while it exists", "no visible change when the cube was placed (diff " + fmt(ab) + "): stale chunk mesh");
            s.check(ac < ab * 0.35 + 0.5, "the frame after removal matches the frame before", "cube still visible after removal (diff " + fmt(ac) + " vs " + fmt(ab) + "): stale chunk mesh");
        }
    }

    private void legTimeOfDay(Report.Section s, double ox, double oz) throws Exception {
        perf.setLeg("t70_time");
        long[] t = {12000, 19000, 0, 5000};
        String[] n = {"noon", "sunset", "midnight", "sunrise"};
        float[] yaw = {0, 90, 0, -90};
        float[] pitch = {-30, 6, -45, 6};
        for (int i = 0; i < t.length; i++) {
            setTimeOfDay(t[i]);
            look(yaw[i], pitch[i]);
            bot.ticks(30);
            shot(s, "t70_" + n[i]);
        }
        setTimeOfDay(12000);
        look(0, 10);
    }

    private void legIdle(Report.Section s, double ox, double oz) throws Exception {
        perf.setLeg("t80_idle");
        // standing still in one place: animals around, world ticking (leaf decay, light updates), chunk manager unloading nothing
        int top = bot.client(() -> mc.theWorld.getHeightValue((int) Math.floor(ox), (int) Math.floor(oz)));
        place(ox, top + 3, oz, 0.0F, 12.0F);
        spawnPassiveHerd(ox, top + 1, oz);
        settle(40, 40, 600);
        shot(s, "t80_idle_start");
        long end = System.currentTimeMillis() + IDLE_SECONDS * 1000L;
        boolean mid = false;
        while (System.currentTimeMillis() < end) {
            bot.ticks(20);
            if (!mid && System.currentTimeMillis() > end - IDLE_SECONDS * 500L) {
                mid = true;
                shot(s, "t80_idle_mid");
            }
        }
        shot(s, "t80_idle_end");
    }

    private void spawnPassiveHerd(double ox, int groundTop, double oz) throws Exception {
        String[] names = {"Pig", "Cow", "Sheep", "Chicken", "Pig", "Cow", "Sheep", "Chicken"};
        bot.server(() -> {
            WorldServer w = swOf(sp());
            for (int i = 0; i < names.length; i++) {
                Entity e = EntityList.createEntityByName(names[i], w);
                if (e != null) {
                    e.setLocationAndAngles(ox - 5 + (i % 4) * 3.0, groundTop + 1, oz + 6 + (i / 4) * 3.0, 180.0F, 0.0F);
                    w.spawnEntityInWorld(e);
                }
            }
        });
    }
}
