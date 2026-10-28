package net.xiaoyu233.fml.vulkan.testbot.cmd.nr17;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.AchievementList;
import net.minecraft.Block;
import net.minecraft.BossStatus;
import net.minecraft.EnumGameType;
import net.minecraft.IBossDisplayData;
import net.minecraft.ItemStack;
import net.minecraft.Minecraft;
import net.minecraft.ServerPlayer;
import net.minecraft.World;
import net.minecraft.server.MinecraftServer;
import net.xiaoyu233.fml.vulkan.render.frame.FrameStats;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/**
 * Devtest commands of work package NR-17 (GUI primitives, HUD, toast, chat, debug chart):
 * <ul>
 * <li>{@code hud [scene|all]}: sets up one HUD state at a time on the standard test world (survival / creative, hurt, underwater, pumpkin,
 * F3 with pie chart, chat, tab list, boss bar, toast) and captures it as {@code nr17_hud_<scene>.png}. Without an argument (or {@code all})
 * every scene runs in order and the HUD state is reset between them. The capture list {@code native-captures/NR-17.txt} carries the
 * same scenes with their tolerance class (T0 masked HUD region, T1 full frame).</li>
 * <li>{@code hotbarDraws}: asserts that the hotbar frame (survival HUD, no overlays) issues at most 20 GUI draws. {@link FrameStats} only
 * carries whole-frame draw totals, so the gate fails (is not measurable) until a GUI-phase count exists; it never compares the
 * whole-frame total against the budget.</li>
 * </ul>
 * Runs on the bot thread; the client and server state is changed through {@link TestBot#client(Runnable)} and {@link TestBot#server(Runnable)}.
 */
public final class Commands implements DevCommandProvider {
    /** Scenes of {@code hud}, in run order. */
    static final List<String> HUD_SCENES = List.of("survival", "creative", "hurt", "underwater", "pumpkin", "f3", "chat", "tablist", "bossbar", "toast");
    /** Draw budget of the hotbar frame (design acceptance, ARCHITECTURE-NATIVE 3.11 NR-17). */
    static final int HOTBAR_DRAW_BUDGET = 20;

    @Override
    public String packageId() {
        return "NR-17";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new Hud(), new HotbarDraws());
    }

    // ---------------------------------------------------------------------------------------------------------
    // shared helpers
    // ---------------------------------------------------------------------------------------------------------

    /** The integrated server's first player (the bot's player); server thread only through {@link TestBot#server}. */
    static ServerPlayer serverPlayer() {
        return (ServerPlayer) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
    }

    /** Clears every HUD overlay the scenes set, so the next scene starts from the plain survival HUD. */
    static void resetHud(TestBot bot, Minecraft mc) throws Exception {
        bot.client(() -> {
            mc.gameSettings.showDebugInfo = false;
            mc.gameSettings.showDebugProfilerChart = false;
            mc.gameSettings.keyBindPlayerList.pressed = false;
            mc.thePlayer.hurtTime = 0;
            mc.thePlayer.hurtResistantTime = 0;
            mc.thePlayer.inventory.armorInventory[3] = null;
            mc.ingameGUI.getChatGUI().clearChatMessages();
            BossStatus.bossName = null;
            BossStatus.statusBarLength = 0;
        });
        bot.server(() -> {
            ServerPlayer p = serverPlayer();
            p.setGameType(EnumGameType.CREATIVE);
            p.capabilities.isFlying = true;
            p.capabilities.allowFlying = true;
            p.sendPlayerAbilities();
            restoreWater(p.worldObj);
        });
    }

    // cells replaced by water in the underwater scene: key "x,y,z" -> original block id
    private static final java.util.Map<String, Integer> WATER_UNDO = new java.util.LinkedHashMap<>();

    /** Replaces the cells around the player's eyes with still water (server side) so the client's eye-in-water test passes. */
    static void placeWater(ServerPlayer p) {
        World w = p.worldObj;
        int x = (int) Math.floor(p.posX);
        int y = (int) Math.floor(p.posY + p.getEyeHeight());
        int z = (int) Math.floor(p.posZ);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int bx = x + dx;
                    int by = y + dy;
                    int bz = z + dz;
                    String key = bx + "," + by + "," + bz;
                    if (!WATER_UNDO.containsKey(key)) {
                        WATER_UNDO.put(key, w.getBlockId(bx, by, bz));
                    }
                    w.setBlock(bx, by, bz, Block.waterStill.blockID, 0, 3);
                }
            }
        }
    }

    /** Puts back every cell {@link #placeWater} changed. */
    static void restoreWater(World w) {
        for (java.util.Map.Entry<String, Integer> e : WATER_UNDO.entrySet()) {
            String[] c = e.getKey().split(",");
            w.setBlock(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]), e.getValue(), 0, 3);
        }
        WATER_UNDO.clear();
    }

    /** Sets the HUD state of one scene (the reset has run before). */
    static void applyScene(TestBot bot, Minecraft mc, String scene) throws Exception {
        switch (scene) {
            case "survival" -> bot.server(() -> {
                ServerPlayer p = serverPlayer();
                p.setGameType(EnumGameType.SURVIVAL);
                p.capabilities.isFlying = false;
                p.capabilities.allowFlying = false;
                p.sendPlayerAbilities();
                p.setHealth(p.getMaxHealth());
            });
            case "creative" -> bot.server(() -> {
                ServerPlayer p = serverPlayer();
                p.setGameType(EnumGameType.CREATIVE);
                p.capabilities.isFlying = true;
                p.capabilities.allowFlying = true;
                p.sendPlayerAbilities();
            });
            case "hurt" -> bot.client(() -> {
                mc.thePlayer.hurtTime = mc.thePlayer.maxHurtTime > 0 ? mc.thePlayer.maxHurtTime : 10;
                mc.thePlayer.hurtResistantTime = mc.thePlayer.maxHurtResistantTime;
            });
            case "underwater" -> bot.server(() -> {
                ServerPlayer p = serverPlayer();
                p.setGameType(EnumGameType.SURVIVAL);
                p.capabilities.isFlying = false;
                p.sendPlayerAbilities();
                placeWater(p);
            });
            case "pumpkin" -> bot.client(() -> mc.thePlayer.inventory.armorInventory[3] = new ItemStack(Block.pumpkin));
            case "f3" -> bot.client(() -> {
                mc.gameSettings.showDebugInfo = true;
                mc.gameSettings.showDebugProfilerChart = true;
            });
            case "chat" -> bot.client(() -> {
                mc.ingameGUI.getChatGUI().printChatMessage("<nr17> HUD chat line one");
                mc.ingameGUI.getChatGUI().printChatMessage("<nr17> HUD chat line two");
            });
            case "tablist" -> bot.client(() -> mc.gameSettings.keyBindPlayerList.pressed = true);
            case "bossbar" -> bot.client(() -> {
                BossStatus.setBossStatus(new IBossDisplayData() {
                    @Override
                    public float getMaxHealth() {
                        return 200.0F;
                    }

                    @Override
                    public float getHealth() {
                        return 150.0F;
                    }

                    @Override
                    public String getEntityName() {
                        return "NR-17 test boss";
                    }
                }, false);
                BossStatus.statusBarLength = 100;
            });
            case "toast" -> bot.client(() -> mc.guiAchievement.queueTakenAchievement(AchievementList.openInventory));
            default -> throw new IllegalArgumentException("unknown hud scene '" + scene + "' (one of " + HUD_SCENES + ")");
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // hud
    // ---------------------------------------------------------------------------------------------------------

    /** {@code hud [scene|all]}: HUD scene set-up and capture, see the class comment. */
    private static final class Hud implements DevCommand {
        @Override
        public String name() {
            return "hud";
        }

        @Override
        public String description() {
            return "HUD scene set-up and capture (survival/creative, hurt, underwater, pumpkin, f3, chat, tablist, bossbar, toast): hud [scene|all]";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            TestBot bot = TestBot.get();
            if (bot == null) {
                s.warn("hud", "test bot not running");
                return;
            }
            Minecraft mc = bot.minecraft();
            String which = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "all";
            List<String> scenes = new ArrayList<>();
            if (which.equals("all")) {
                scenes.addAll(HUD_SCENES);
            } else if (HUD_SCENES.contains(which)) {
                scenes.add(which);
            } else {
                s.fail("hud", "unknown scene '" + which + "' (one of " + HUD_SCENES + " or all)");
                return;
            }
            for (String scene : scenes) {
                try {
                    resetHud(bot, mc);
                    applyScene(bot, mc, scene);
                    bot.frames(20);
                    String path = bot.screenshot(s, "nr17_hud_" + scene);
                    s.check(path != null, "hud " + scene, "no capture written");
                    s.info("hud " + scene, "captured " + (path == null ? "-" : path));
                } catch (Exception e) {
                    s.exception("hud " + scene, e);
                }
            }
            resetHud(bot, mc);
            bot.client(() -> {
                mc.gameSettings.showDebugInfo = false;
                mc.gameSettings.showDebugProfilerChart = false;
            });
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // hotbarDraws
    // ---------------------------------------------------------------------------------------------------------

    /** {@code hotbarDraws}: GUI draws of the hotbar frame from {@link FrameStats} against {@link #HOTBAR_DRAW_BUDGET}. */
    private static final class HotbarDraws implements DevCommand {
        @Override
        public String name() {
            return "hotbarDraws";
        }

        @Override
        public String description() {
            return "assert the hotbar frame issues <= " + HOTBAR_DRAW_BUDGET + " GUI draws (FrameStats)";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            TestBot bot = TestBot.get();
            if (bot == null) {
                s.warn("hotbarDraws", "test bot not running");
                return;
            }
            Minecraft mc = bot.minecraft();
            resetHud(bot, mc);
            applyScene(bot, mc, "survival");
            bot.frames(30);
            long before = FrameStats.frameCount();
            bot.frames(10);
            int maxWholeFrame = 0;
            int counted = 0;
            for (int ago = 0; ago < 10; ago++) {
                FrameStats.Frame f = FrameStats.ago(ago);
                if (f == null || f.index() < before) {
                    continue;
                }
                counted++;
                maxWholeFrame = Math.max(maxWholeFrame, f.draws());
            }
            if (counted == 0) {
                s.fail("hotbarDraws", "no frame recorded in the window (FrameStats ring empty); hotbar budget not measured");
            } else {
                // FrameStats.Frame.draws() is the whole-frame total (terrain, entities, sky, GUI). The budget is for the GUI phase
                // only, and FrameStats has no per-phase count, so the budget cannot be measured from it. Comparing the whole-frame
                // total against 20 would be a wrong measurement, so this gate fails until a GUI-phase count is available.
                s.fail("hotbarDraws", "hotbar GUI-phase draws not measurable: FrameStats has only the whole-frame count (max "
                        + maxWholeFrame + " over " + counted + " frames, not compared with budget " + HOTBAR_DRAW_BUDGET + ")");
            }
            resetHud(bot, mc);
        }
    }
}
