package net.xiaoyu233.fml.vulkan.test;

import net.minecraft.Entity;
import net.minecraft.EntityItem;
import net.minecraft.EntityList;
import net.minecraft.EntityXPOrb;
import net.minecraft.PotionEffect;
import net.minecraft.EnumGameType;
import net.minecraft.EnumParticle;
import net.minecraft.GuiChat;
import net.minecraft.GuiIngameMenu;
import net.minecraft.GuiInventory;
import net.minecraft.GuiOptions;
import net.minecraft.ItemStack;
import net.minecraft.Minecraft;
import net.minecraft.ServerPlayer;
import net.minecraft.WorldServer;
import net.minecraft.server.MinecraftServer;
import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.core.RenderCore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Test harness: a scripted scenario for an unattended run, active only with {@code -Dfml.vulkan.autoScript=<file>} (usually together
 * with {@code fml.vulkan.autoWorld}).
 * <p>
 * File format: one step per line, {@code <frame> <command> [arguments]}; {@code #} starts a comment, blank lines are ignored. The
 * frame is the number of game loop iterations since the player first existed in the world (a step runs in the first iteration whose
 * count is greater than or equal to it, steps keep file order). Commands:
 * <pre>
 *   chat &lt;text&gt;                          send chat / a command as the player
 *   echo &lt;text&gt;                          log a marker line
 *   shot &lt;name&gt;                          capture the frame that shows the state after this step to vk-captures/&lt;name&gt;.png
 *   dumptex &lt;from&gt; &lt;to&gt;                 write the GL textures with these names to vk-captures/tex_&lt;name&gt;_&lt;w&gt;x&lt;h&gt;.png (RGBA, via glGetTexImage)
 *   trace                                 log every draw of the frame that shows the state after this step (same frame as a shot)
 *   exit                                  shut the game down
 *   look &lt;yaw&gt; &lt;pitch&gt;                   set the camera (degrees; yaw 0 = south, 90 = west, -90 = east)
 *   tp &lt;x&gt; &lt;y&gt; &lt;z&gt; [yaw pitch]          teleport the server side player (accepts ~ relative values)
 *   fly &lt;on|off&gt;                         creative mode + flying
 *   time &lt;ticks&gt;                         set the overworld total world time (server and client)
 *   setblock &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;id&gt; [meta]   (accepts ~ relative values, relative to the player's block position)
 *   fill &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt; &lt;id&gt; [meta]
 *   summon &lt;EntityName&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;     (accepts ~ relative values)
 *   item &lt;slot 0-8&gt; &lt;id&gt; [count] [meta]  put a stack into the hotbar (server side)
 *   drift &lt;vx&gt; &lt;vy&gt; &lt;vz&gt;               fly with this velocity (blocks per tick, applied every frame; 0 0 0 stops)
 *   selectall                             select all text in the text fields of the open screen
 *   guiclass &lt;fqcn&gt;                     open a screen class with a (GuiScreen parent) constructor (mod screens)
 *   screenshot                            F2 screenshot (glReadPixels path) into screenshots/
 *   fullscreen                            toggle fullscreen (Minecraft.toggleFullscreen)
 *   resize &lt;w&gt; &lt;h&gt;                      Display.setDisplayMode(w x h) (framebuffer pixels)
 *   drop &lt;id&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; [count]      spawn a dropped item entity (accepts ~ relative values)
 *   xp &lt;x&gt; &lt;y&gt; &lt;z&gt; [amount]            spawn an experience orb
 *   enchant &lt;slot&gt; [level]              enchant the hotbar stack (sharpness: shows the glint)
 *   lightning &lt;x&gt; &lt;y&gt; &lt;z&gt;              strike a lightning bolt (accepts ~ relative values)
 *   burn &lt;seconds&gt;                       set the player on fire
 *   armor &lt;slot 0 boots..3 helmet&gt; &lt;id&gt;  wear an armor piece (id 0 removes it)
 *   potion &lt;effect id&gt; &lt;ticks&gt;           give the player a potion effect
 *   select &lt;slot 0-8&gt;                    select a hotbar slot
 *   swing                                 swing the arm
 *   gui &lt;none|inventory|menu|options|chat|singleplayer|multiplayer|video|controls|createworld|language&gt;
 * A step line starting with {@code m<frame>} instead of {@code <frame>} runs while no world is loaded (main menu tests).
 *   key &lt;GLFW key code&gt; [down|up|tap]  synthetic key event through InputManager.onKey (F3 = 292)
 *   debug &lt;on|off&gt;                       F3 style debug text
 *   view &lt;0|1|2&gt;                         first person / back / front camera
 *   opt &lt;ao|clouds|particles|gamma|fov|guimode&gt; &lt;value&gt;
 *   particle &lt;EnumParticle name&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; [count]   (accepts ~ relative values)
 *   weather &lt;clear|rain&gt;                 client side rain strength 0 / 1
 * </pre>
 * Server side changes are queued and executed on the server thread ({@link #runServerTasks()} is called from {@code MinecraftServer.tick}).
 */
public final class AutoScript {
    private static final class Step {
        final long frame;
        final String line;
        /** Runs while no world is loaded ({@code m<frame>} prefix), counted in game loop iterations since the script started. */
        final boolean menu;
        boolean done;

        Step(long frame, String line, boolean menu) {
            this.frame = frame;
            this.line = line;
            this.menu = menu;
        }
    }

    private static final ConcurrentLinkedQueue<Runnable> SERVER_TASKS = new ConcurrentLinkedQueue<>();
    private static List<Step> steps;
    private static boolean loaded;
    private static long playerFrames;
    private static long menuFrames;
    /** {@code drift}: constant client side velocity (blocks per tick) applied every frame while flying, null = none. */
    private static double[] drift;
    private static boolean exitRequested;

    private AutoScript() {
    }

    /** True when {@code fml.vulkan.autoScript} names a readable script. */
    public static boolean active() {
        load();
        return steps != null;
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        String property = System.getProperty("fml.vulkan.autoScript");
        if (property == null || property.trim().isEmpty()) {
            return;
        }
        Path file = Paths.get(property.trim());
        try {
            List<Step> list = new ArrayList<>();
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int space = line.indexOf(' ');
                if (space < 0) {
                    VkLog.LOGGER.warn("[autoScript] ignored line without command: {}", line);
                    continue;
                }
                try {
                    String head = line.substring(0, space);
                    boolean menu = head.startsWith("m");
                    list.add(new Step(Long.parseLong(menu ? head.substring(1) : head), line.substring(space + 1).trim(), menu));
                } catch (NumberFormatException e) {
                    VkLog.LOGGER.warn("[autoScript] ignored line with an invalid frame: {}", line);
                }
            }
            steps = list;
            VkLog.LOGGER.info("[autoScript] {} steps loaded from {}", list.size(), file);
        } catch (IOException e) {
            VkLog.LOGGER.error("[autoScript] cannot read {}: {}", file, e.toString());
        }
    }

    /** Called at the end of every {@code Minecraft.runGameLoop} iteration. */
    public static void tick(Minecraft mc) {
        if (!active() || exitRequested) {
            return;
        }
        boolean inWorld = mc.thePlayer != null && mc.theWorld != null;
        long now = inWorld ? ++playerFrames : ++menuFrames;
        if (inWorld && drift != null) {
            mc.thePlayer.motionX = drift[0];
            mc.thePlayer.motionY = drift[1];
            mc.thePlayer.motionZ = drift[2];
        }
        for (Step step : steps) {
            if (step.done || step.menu == inWorld) {
                continue;
            }
            if (step.frame > now) {
                continue;
            }
            step.done = true;
            try {
                run(mc, step);
            } catch (Throwable t) {
                VkLog.LOGGER.error("[autoScript] step failed: {} {}", step.frame, step.line, t);
            }
        }
    }

    /** Called from {@code MinecraftServer.tick} (server thread). */
    public static void runServerTasks() {
        Runnable task;
        while ((task = SERVER_TASKS.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                VkLog.LOGGER.error("[autoScript] server task failed", t);
            }
        }
    }

    private static ServerPlayer serverPlayer() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager().playerEntityList.isEmpty()) {
            return null;
        }
        return (ServerPlayer) server.getConfigurationManager().playerEntityList.get(0);
    }

    /** Reads GL textures back through glGetTexImage and writes them (with alpha) as PNG: shows what the backend really stores. */
    private static void dumpTextures(int from, int to) {
        Path dir = net.xiaoyu233.fml.vulkan.VulkanConfig.captureDir();
        int previous = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        for (int name = from; name <= to; name++) {
            if (!org.lwjgl.opengl.GL11.glIsTexture(name)) {
                continue;
            }
            try {
                org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, name);
                int w = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
                int h = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);
                if (w <= 0 || h <= 0) {
                    VkLog.LOGGER.info("[autoScript] texture {} has no level 0", name);
                    continue;
                }
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(w * h * 4);
                org.lwjgl.opengl.GL11.glGetTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_RGBA,
                        org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, buf);
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                long alphaSum = 0;
                for (int i = 0; i < w * h; i++) {
                    int r = buf.get(i * 4) & 255, g = buf.get(i * 4 + 1) & 255, b = buf.get(i * 4 + 2) & 255, a = buf.get(i * 4 + 3) & 255;
                    alphaSum += a;
                    image.setRGB(i % w, i / w, a << 24 | r << 16 | g << 8 | b);
                }
                Files.createDirectories(dir);
                javax.imageio.ImageIO.write(image, "png", dir.resolve("tex_" + name + "_" + w + "x" + h + ".png").toFile());
                VkLog.LOGGER.info("[autoScript] texture {} {}x{} mean alpha {}", name, w, h, alphaSum / (w * h));
            } catch (Throwable t) {
                VkLog.LOGGER.warn("[autoScript] cannot dump texture {}: {}", name, t.toString());
            }
        }
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, previous);
    }

    private static double rel(String token, double base) {
        if (token.startsWith("~")) {
            return base + (token.length() > 1 ? Double.parseDouble(token.substring(1)) : 0.0);
        }
        return Double.parseDouble(token);
    }

    private static void run(Minecraft mc, Step step) {
        String[] t = step.line.split("\\s+");
        String cmd = t[0].toLowerCase();
        VkLog.LOGGER.info("[autoScript] @{}{} {}", step.menu ? "m" : "", step.menu ? menuFrames : playerFrames, step.line);
        switch (cmd) {
            case "chat" -> mc.thePlayer.sendChatMessage(step.line.substring(4).trim());
            case "echo" -> VkLog.LOGGER.info("[autoScript] ECHO {}", step.line.substring(4).trim());
            case "shot" -> RenderCore.requestCapture(t[1], 1); // MITE presents at the start of the next loop iteration
            case "dumptex" -> dumpTextures(Integer.parseInt(t[1]), Integer.parseInt(t[2]));
            case "trace" -> net.xiaoyu233.fml.vulkan.gl.draw.DrawSystem.setTraceFrame(RenderCore.frameNumber() + 1);
            case "exit" -> {
                exitRequested = true;
                mc.shutdown();
            }
            case "look" -> {
                float yaw = Float.parseFloat(t[1]);
                float pitch = Float.parseFloat(t[2]);
                mc.thePlayer.rotationYaw = yaw;
                mc.thePlayer.prevRotationYaw = yaw;
                mc.thePlayer.rotationYawHead = yaw;
                mc.thePlayer.prevRotationYawHead = yaw;
                mc.thePlayer.rotationPitch = pitch;
                mc.thePlayer.prevRotationPitch = pitch;
            }
            case "tp" -> {
                double px = mc.thePlayer.posX, py = mc.thePlayer.posY, pz = mc.thePlayer.posZ;
                double x = rel(t[1], px), y = rel(t[2], py), z = rel(t[3], pz);
                float yaw = t.length > 5 ? Float.parseFloat(t[4]) : mc.thePlayer.rotationYaw;
                float pitch = t.length > 5 ? Float.parseFloat(t[5]) : mc.thePlayer.rotationPitch;
                SERVER_TASKS.add(() -> {
                    ServerPlayer p = serverPlayer();
                    if (p != null) {
                        p.playerNetServerHandler.setPlayerLocation(x, y, z, yaw, pitch);
                    }
                });
            }
            case "fly" -> {
                boolean on = !"off".equalsIgnoreCase(t[1]);
                SERVER_TASKS.add(() -> {
                    ServerPlayer p = serverPlayer();
                    if (p != null) {
                        p.setGameType(on ? EnumGameType.CREATIVE : EnumGameType.SURVIVAL);
                        p.capabilities.allowFlying = on;
                        p.capabilities.isFlying = on;
                        p.sendPlayerAbilities();
                    }
                });
                mc.thePlayer.capabilities.allowFlying = on;
                mc.thePlayer.capabilities.isFlying = on;
            }
            case "time" -> {
                long ticks = Long.parseLong(t[1]);
                SERVER_TASKS.add(() -> {
                    WorldServer w = MinecraftServer.getServer().worldServers[0];
                    w.getWorldInfo().setTotalWorldTime(ticks, w);
                });
                mc.theWorld.getWorldInfo().setTotalWorldTime(ticks, mc.theWorld);
            }
            case "setblock" -> {
                int x = (int) Math.floor(rel(t[1], mc.thePlayer.posX));
                int y = (int) Math.floor(rel(t[2], mc.thePlayer.posY));
                int z = (int) Math.floor(rel(t[3], mc.thePlayer.posZ));
                int id = Integer.parseInt(t[4]);
                int meta = t.length > 5 ? Integer.parseInt(t[5]) : 0;
                SERVER_TASKS.add(() -> MinecraftServer.getServer().worldServers[0].setBlock(x, y, z, id, meta, 3));
            }
            case "fill" -> {
                int x1 = (int) Math.floor(rel(t[1], mc.thePlayer.posX));
                int y1 = (int) Math.floor(rel(t[2], mc.thePlayer.posY));
                int z1 = (int) Math.floor(rel(t[3], mc.thePlayer.posZ));
                int x2 = (int) Math.floor(rel(t[4], mc.thePlayer.posX));
                int y2 = (int) Math.floor(rel(t[5], mc.thePlayer.posY));
                int z2 = (int) Math.floor(rel(t[6], mc.thePlayer.posZ));
                int id = Integer.parseInt(t[7]);
                int meta = t.length > 8 ? Integer.parseInt(t[8]) : 0;
                SERVER_TASKS.add(() -> {
                    WorldServer w = MinecraftServer.getServer().worldServers[0];
                    for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                        for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                                w.setBlock(x, y, z, id, meta, 2);
                            }
                        }
                    }
                });
            }
            case "summon" -> {
                String name = t[1];
                double x = rel(t[2], mc.thePlayer.posX), y = rel(t[3], mc.thePlayer.posY), z = rel(t[4], mc.thePlayer.posZ);
                SERVER_TASKS.add(() -> {
                    WorldServer w = MinecraftServer.getServer().worldServers[0];
                    Entity e = EntityList.createEntityByName(name, w);
                    if (e == null) {
                        VkLog.LOGGER.warn("[autoScript] unknown entity {}", name);
                        return;
                    }
                    e.setLocationAndAngles(x, y, z, 0.0F, 0.0F);
                    w.spawnEntityInWorld(e);
                });
            }
            case "drift" -> {
                double vx = Double.parseDouble(t[1]), vy = Double.parseDouble(t[2]), vz = Double.parseDouble(t[3]);
                drift = vx == 0.0 && vy == 0.0 && vz == 0.0 ? null : new double[]{vx, vy, vz};
            }
            case "selectall" -> {
                // select the whole text of every GuiTextField of the open screen (the selection highlight uses GL_COLOR_LOGIC_OP)
                net.minecraft.GuiScreen screen = mc.currentScreen;
                for (Class<?> c = screen == null ? null : screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (net.minecraft.GuiTextField.class.isAssignableFrom(f.getType())) {
                            try {
                                f.setAccessible(true);
                                net.minecraft.GuiTextField tf = (net.minecraft.GuiTextField) f.get(screen);
                                if (tf != null) {
                                    tf.setFocused(true);
                                    tf.setCursorPositionZero();
                                    tf.setSelectionPos(tf.getText().length());
                                }
                            } catch (ReflectiveOperationException | RuntimeException e) {
                                VkLog.LOGGER.warn("[autoScript] selectall failed: {}", e.toString());
                            }
                        }
                    }
                }
            }
            case "guiclass" -> {
                try {
                    Class<?> c = Class.forName(t[1]);
                    mc.displayGuiScreen((net.minecraft.GuiScreen) c.getConstructor(net.minecraft.GuiScreen.class).newInstance(mc.currentScreen));
                } catch (ReflectiveOperationException e) {
                    VkLog.LOGGER.warn("[autoScript] cannot open {}: {}", t[1], e.toString());
                }
            }
            case "screenshot" -> mc.take_screenshot_next_tick = true;
            case "fullscreen" -> mc.toggleFullscreen();
            case "resize" -> {
                try {
                    org.lwjgl.opengl.Display.setDisplayMode(new org.lwjgl.opengl.DisplayMode(Integer.parseInt(t[1]), Integer.parseInt(t[2])));
                } catch (org.lwjgl.LWJGLException e) {
                    VkLog.LOGGER.warn("[autoScript] resize failed: {}", e.toString());
                }
            }
            case "drop" -> {
                int id = Integer.parseInt(t[1]);
                double x = rel(t[2], mc.thePlayer.posX), y = rel(t[3], mc.thePlayer.posY), z = rel(t[4], mc.thePlayer.posZ);
                int count = t.length > 5 ? Integer.parseInt(t[5]) : 1;
                SERVER_TASKS.add(() -> {
                    WorldServer w = MinecraftServer.getServer().worldServers[0];
                    EntityItem e = new EntityItem(w, x, y, z, new ItemStack(id, count, 0));
                    e.motionX = 0.0;
                    e.motionY = 0.0;
                    e.motionZ = 0.0;
                    w.spawnEntityInWorld(e);
                });
            }
            case "xp" -> {
                double x = rel(t[1], mc.thePlayer.posX), y = rel(t[2], mc.thePlayer.posY), z = rel(t[3], mc.thePlayer.posZ);
                int amount = t.length > 4 ? Integer.parseInt(t[4]) : 5;
                SERVER_TASKS.add(() -> {
                    WorldServer w = MinecraftServer.getServer().worldServers[0];
                    w.spawnEntityInWorld(new EntityXPOrb(w, x, y, z, amount));
                });
            }
            case "enchant" -> {
                int slot = Integer.parseInt(t[1]);
                int level = t.length > 2 ? Integer.parseInt(t[2]) : 3;
                SERVER_TASKS.add(() -> {
                    ServerPlayer p = serverPlayer();
                    if (p != null && p.inventory.mainInventory[slot] != null) {
                        p.inventory.mainInventory[slot].addEnchantment(net.minecraft.Enchantment.sharpness, level);
                        p.inventoryContainer.detectAndSendChanges();
                    }
                });
            }
            case "lightning" -> {
                double x = rel(t[1], mc.thePlayer.posX), y = rel(t[2], mc.thePlayer.posY), z = rel(t[3], mc.thePlayer.posZ);
                SERVER_TASKS.add(() -> {
                    WorldServer w = MinecraftServer.getServer().worldServers[0];
                    w.addWeatherEffect(new net.minecraft.EntityLightningBolt(w, x, y, z));
                });
            }
            case "burn" -> {
                int seconds = Integer.parseInt(t[1]);
                SERVER_TASKS.add(() -> {
                    ServerPlayer p = serverPlayer();
                    if (p != null) {
                        p.setFire(seconds);
                    }
                });
            }
            case "armor" -> {
                int slot = Integer.parseInt(t[1]); // 0 boots .. 3 helmet
                int id = Integer.parseInt(t[2]);
                SERVER_TASKS.add(() -> {
                    ServerPlayer p = serverPlayer();
                    if (p != null) {
                        p.inventory.armorInventory[slot] = id <= 0 ? null : new ItemStack(id, 1, 0);
                        p.inventoryContainer.detectAndSendChanges();
                    }
                });
            }
            case "potion" -> {
                int id = Integer.parseInt(t[1]);
                int ticks = Integer.parseInt(t[2]);
                SERVER_TASKS.add(() -> {
                    ServerPlayer p = serverPlayer();
                    if (p != null) {
                        p.addPotionEffect(new PotionEffect(id, ticks, 0));
                    }
                });
            }
            case "item" -> {
                int slot = Integer.parseInt(t[1]);
                int id = Integer.parseInt(t[2]);
                int count = t.length > 3 ? Integer.parseInt(t[3]) : 1;
                int meta = t.length > 4 ? Integer.parseInt(t[4]) : 0;
                SERVER_TASKS.add(() -> {
                    ServerPlayer p = serverPlayer();
                    if (p != null) {
                        p.inventory.mainInventory[slot] = new ItemStack(id, count, meta);
                        p.inventoryContainer.detectAndSendChanges();
                    }
                });
            }
            case "select" -> {
                int slot = Integer.parseInt(t[1]);
                mc.thePlayer.inventory.currentItem = slot;
                SERVER_TASKS.add(() -> {
                    ServerPlayer p = serverPlayer();
                    if (p != null) {
                        p.inventory.currentItem = slot;
                    }
                });
            }
            case "swing" -> mc.thePlayer.swingArm();
            case "gui" -> {
                switch (t[1]) {
                    case "none" -> mc.displayGuiScreen(null);
                    case "inventory" -> mc.displayGuiScreen(new GuiInventory(mc.thePlayer));
                    case "menu" -> mc.displayGuiScreen(new GuiIngameMenu());
                    case "options" -> mc.displayGuiScreen(new GuiOptions(null, mc.gameSettings));
                    case "chat" -> mc.displayGuiScreen(new GuiChat());
                    case "singleplayer" -> mc.displayGuiScreen(new net.minecraft.GuiSelectWorld(mc.currentScreen));
                    case "multiplayer" -> mc.displayGuiScreen(new net.minecraft.GuiMultiplayer(mc.currentScreen));
                    case "video" -> mc.displayGuiScreen(new net.minecraft.GuiVideoSettings(mc.currentScreen, mc.gameSettings));
                    case "controls" -> mc.displayGuiScreen(new net.minecraft.GuiControls(mc.currentScreen, mc.gameSettings));
                    case "createworld" -> mc.displayGuiScreen(new net.minecraft.GuiCreateWorld(mc.currentScreen));
                    case "language" -> mc.displayGuiScreen(new net.minecraft.GuiLanguage(mc.currentScreen, mc.gameSettings, mc.getLanguageManager()));
                    default -> VkLog.LOGGER.warn("[autoScript] unknown gui {}", t[1]);
                }
            }
            case "key" -> {
                // key <GLFW key code> [down|up|tap]: a synthetic keyboard event through the real GLFW key path (InputManager.onKey)
                int glfwKey = Integer.parseInt(t[1]);
                String mode = t.length > 2 ? t[2].toLowerCase() : "tap";
                if (!mode.equals("up")) {
                    net.xiaoyu233.fml.vulkan.window.InputManager.onKey(glfwKey, 0, 1, 0);
                }
                if (!mode.equals("down")) {
                    net.xiaoyu233.fml.vulkan.window.InputManager.onKey(glfwKey, 0, 0, 0);
                }
            }
            case "debug" -> mc.gameSettings.showDebugInfo = !"off".equalsIgnoreCase(t[1]);
            case "view" -> mc.gameSettings.thirdPersonView = Integer.parseInt(t[1]);
            case "opt" -> {
                switch (t[1]) {
                    case "ao" -> mc.gameSettings.ambientOcclusion = Integer.parseInt(t[2]);
                    case "clouds" -> mc.gameSettings.clouds = Boolean.parseBoolean(t[2]);
                    case "particles" -> mc.gameSettings.particleSetting = Integer.parseInt(t[2]);
                    case "gamma" -> mc.gameSettings.gammaSetting = Float.parseFloat(t[2]);
                    case "fov" -> mc.gameSettings.fovSetting = Float.parseFloat(t[2]);
                    case "pauseonlostfocus" -> mc.gameSettings.pauseOnLostFocus = Boolean.parseBoolean(t[2]);
                    case "guimode" -> mc.gameSettings.gui_mode = Integer.parseInt(t[2]);
                    default -> VkLog.LOGGER.warn("[autoScript] unknown option {}", t[1]);
                }
            }
            case "particle" -> {
                EnumParticle particle = EnumParticle.valueOf(t[1]);
                double x = rel(t[2], mc.thePlayer.posX), y = rel(t[3], mc.thePlayer.posY), z = rel(t[4], mc.thePlayer.posZ);
                int count = t.length > 5 ? Integer.parseInt(t[5]) : 1;
                for (int i = 0; i < count; i++) {
                    mc.theWorld.spawnParticle(particle, x, y, z, 0.0, 0.0, 0.0);
                }
            }
            case "weather" -> {
                float strength = "rain".equalsIgnoreCase(t[1]) ? 1.0F : 0.0F;
                mc.theWorld.setRainStrength(strength);
            }
            default -> VkLog.LOGGER.warn("[autoScript] unknown command {}", cmd);
        }
    }
}
