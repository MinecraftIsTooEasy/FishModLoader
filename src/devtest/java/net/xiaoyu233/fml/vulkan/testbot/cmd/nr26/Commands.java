package net.xiaoyu233.fml.vulkan.testbot.cmd.nr26;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import net.minecraft.Block;
import net.minecraft.GuiCreateFlatWorld;
import net.minecraft.GuiCreateWorld;
import net.minecraft.GuiEditSign;
import net.minecraft.GuiFlatPresets;
import net.minecraft.GuiMainMenu;
import net.minecraft.GuiScreen;
import net.minecraft.GuiScreenTemporaryResourcePackSelect;
import net.minecraft.GuiSlot;
import net.minecraft.GuiStats;
import net.minecraft.GuiWinGame;
import net.minecraft.Minecraft;
import net.minecraft.TileEntitySign;
import net.minecraft.WorldClient;
import net.xiaoyu233.fml.vulkan.testbot.Frozen;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.Stage;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;
import net.xiaoyu233.fml.vulkan.window.InputManager;

/**
 * Devtest commands of work package NR-26: GUI lists and the remaining screens ({@code resourcePacks}, {@code statsScreen}, {@code flatPresets},
 * {@code credits}, {@code signEdit}, {@code slotScroll}). The screens are opened on the client thread, pinned to fixed counters, and captured by the
 * scenes of {@code native-captures/NR-26.txt} (world select and multiplayer list are plain {@code menu} scenes). Verify-only for GL: no GL calls here.
 */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-26";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new ResourcePacks(), new StatsScreen(), new FlatPresets(), new Credits(), new SignEdit(), new SlotScroll());
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Opens a screen on the client thread and lets two frames pass so that {@code initGui} has run. */
    static void show(GuiScreen screen) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        TestBot bot = TestBot.get();
        bot.client(() -> mc.displayGuiScreen(screen));
        bot.frames(10);
    }

    /** Resource pack selection (GuiScreenTemporaryResourcePackSelect) over the dirt background. */
    private static final class ResourcePacks implements DevCommand {
        @Override
        public String name() {
            return "resourcePacks";
        }

        @Override
        public String description() {
            return "open the resource pack selection screen (GuiScreenTemporaryResourcePackSelect) for a capture: resourcePacks";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            Minecraft mc = Minecraft.getMinecraft();
            show(new GuiScreenTemporaryResourcePackSelect(new GuiMainMenu(), mc.gameSettings));
            s.info("resourcePacks", "opened " + mc.currentScreen.getClass().getSimpleName());
        }
    }

    /** Statistics screen (GuiStats) with its general list selected. */
    private static final class StatsScreen implements DevCommand {
        @Override
        public String name() {
            return "statsScreen";
        }

        @Override
        public String description() {
            return "open the statistics screen (GuiStats, general list) for a capture: statsScreen";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            Minecraft mc = Minecraft.getMinecraft();
            show(new GuiStats(new GuiMainMenu(), mc.statFileWriter));
            s.info("statsScreen", "opened " + mc.currentScreen.getClass().getSimpleName());
        }
    }

    /** Flat world presets (GuiFlatPresets) opened from a flat world customisation with the default generator. */
    private static final class FlatPresets implements DevCommand {
        @Override
        public String name() {
            return "flatPresets";
        }

        @Override
        public String description() {
            return "open the flat world presets screen (GuiFlatPresets, default generator) for a capture: flatPresets";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            GuiCreateWorld createWorld = new GuiCreateWorld(new GuiMainMenu());
            // null selects the default flat generator (FlatGeneratorInfo.createFlatGeneratorFromString)
            show(new GuiFlatPresets(new GuiCreateFlatWorld(createWorld, null)));
            s.info("flatPresets", "opened " + Minecraft.getMinecraft().currentScreen.getClass().getSimpleName());
        }
    }

    /**
     * Credits (GuiWinGame) at a fixed scroll position: {@code updateCounter} is pinned (args {@code [tick=300]}) because the credits only move
     * in {@code updateScreen}, which the frozen mode does not run. The pin keeps the scroll identical between runs.
     */
    private static final class Credits implements DevCommand {
        private static final int DEFAULT_TICK = 300;

        @Override
        public String name() {
            return "credits";
        }

        @Override
        public String description() {
            return "open the credits (GuiWinGame) pinned to a fixed scroll tick for a capture: credits [tick=300]";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            int tick = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_TICK;
            GuiWinGame credits = new GuiWinGame();
            show(credits);
            TestBot.get().client(() -> Frozen.setField(credits, "updateCounter", tick));
            s.info("credits", "pinned updateCounter " + tick);
        }
    }

    /**
     * Sign editing (GuiEditSign) at T0 (cursor and counters at creation) or T1 (counter advanced by 20 ticks, so the cursor is in its other
     * blink phase). Args: {@code [t0|t1]}, default t0. The sign is a client-side tile entity placed high above the museum (y = 200) so the
     * museum is not touched; {@code GuiEditSign.onGuiClosed} still sends its update packet, which the server ignores for a missing tile.
     */
    private static final class SignEdit implements DevCommand {
        private static final int SIGN_Y = 200;
        private static final String[] TEXT = {"NR-26", "sign", "", ""};

        @Override
        public String name() {
            return "signEdit";
        }

        @Override
        public String description() {
            return "open the sign editor (GuiEditSign) at a fixed time T0 or T1 for a capture: signEdit [t0|t1]";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            boolean t1 = args.length > 0 && args[0].equalsIgnoreCase("t1");
            Stage stage = TestBot.get().stage();
            double[] c = stage.ensureMuseum();
            int x = (int) Math.floor(c[0]);
            int z = (int) Math.floor(c[2]);
            Minecraft mc = Minecraft.getMinecraft();
            GuiEditSign[] editor = new GuiEditSign[1];
            TestBot.get().client(() -> {
                WorldClient world = mc.theWorld;
                world.setBlock(x, SIGN_Y, z, Block.signPost.blockID);
                TileEntitySign sign = new TileEntitySign();
                sign.setWorldObj(world);
                sign.xCoord = x;
                sign.yCoord = SIGN_Y;
                sign.zCoord = z;
                System.arraycopy(TEXT, 0, sign.signText, 0, TEXT.length);
                world.setBlockTileEntity(x, SIGN_Y, z, sign);
                editor[0] = new GuiEditSign(sign);
                mc.displayGuiScreen(editor[0]);
            });
            TestBot.get().frames(10);
            int counter = t1 ? 20 : 0;
            TestBot.get().client(() -> Frozen.setField(editor[0], "updateCounter", counter));
            s.info("signEdit", "sign at " + x + "," + SIGN_Y + "," + z + " updateCounter " + counter + (t1 ? " (T1)" : " (T0)"));
        }
    }

    /**
     * Scripted scroll of a GuiSlot (the statistics list): {@code [notches=4] [drag=30]} (args). Wheel notches are injected through
     * {@link InputManager#onScroll} and consumed by {@code GuiSlot.drawScreen} (Mouse.next / getEventDWheel). Then the cursor is moved onto the
     * scroll bar, the button is pressed, dragged down in steps and released. The scroll offset ({@code amountScrolled}) is logged after every
     * step; the list stays at its final offset for the capture that follows.
     */
    private static final class SlotScroll implements DevCommand {
        private static final int DEFAULT_NOTCHES = 4;
        private static final int DEFAULT_DRAG = 30;
        private static final int DRAG_STEPS = 5;
        private static final int BUTTON_PRESS = 1; // GLFW_PRESS
        private static final int BUTTON_RELEASE = 0; // GLFW_RELEASE

        @Override
        public String name() {
            return "slotScroll";
        }

        @Override
        public String description() {
            return "scripted wheel and drag scroll of the statistics list, logging the offsets: slotScroll [notches=4] [drag=30]";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            int notches = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_NOTCHES;
            int drag = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_DRAG;
            Minecraft mc = Minecraft.getMinecraft();
            TestBot bot = TestBot.get();
            GuiStats stats = new GuiStats(new GuiMainMenu(), mc.statFileWriter);
            show(stats);
            GuiSlot slot = (GuiSlot) Frozen.getField(stats, "selectedSlot");
            if (slot == null) {
                s.fail("slotScroll", "GuiStats has no selected slot");
                return;
            }
            StringBuilder wheel = new StringBuilder();
            float start = amount(bot, slot);
            for (int i = 0; i < notches; i++) {
                bot.client(() -> InputManager.onScroll(0.0, -1.0));
                bot.frames(2);
                wheel.append(' ').append(amount(bot, slot));
            }
            s.info("slotScroll wheel", "offset " + start + " ->" + wheel + " (" + notches + " notches down)");
            float afterWheel = amount(bot, slot);

            // move the cursor onto the scroll bar, press, drag down in steps, release
            int barX = scrollBarX(slot) + 3;
            int barY = (Integer) Frozen.getField(slot, "top") + 10;
            int dx = barX - InputManager.getMouseX();
            int dy = barY - InputManager.getMouseY();
            bot.client(() -> InputManager.injectMouseMove(dx, dy));
            bot.frames(2);
            bot.client(() -> InputManager.onMouseButton(0, BUTTON_PRESS, 0));
            bot.frames(2);
            StringBuilder dragLog = new StringBuilder();
            int step = drag / DRAG_STEPS;
            for (int i = 0; i < DRAG_STEPS; i++) {
                bot.client(() -> InputManager.injectMouseMove(0, step));
                bot.frames(2);
                dragLog.append(' ').append(amount(bot, slot));
            }
            bot.client(() -> InputManager.onMouseButton(0, BUTTON_RELEASE, 0));
            bot.frames(3);
            float end = amount(bot, slot);
            s.info("slotScroll drag", String.format(Locale.ROOT, "step %d px, offsets%s, end %.1f", step, dragLog, end));
            s.check(afterWheel > start, "wheel moves the list down", "offset did not change after the wheel (" + start + " -> " + afterWheel + ")");
            s.check(end > afterWheel, "drag moves the list down", "drag did not change the offset (" + afterWheel + " -> " + end + ")");
        }

        private static float amount(TestBot bot, GuiSlot slot) throws Exception {
            return bot.client(() -> {
                Object v = Frozen.getField(slot, "amountScrolled");
                return v instanceof Float f ? f : Float.NaN;
            });
        }

        private static int scrollBarX(GuiSlot slot) throws Exception {
            Method m = GuiSlot.class.getDeclaredMethod("getScrollBarX");
            m.setAccessible(true);
            return (Integer) m.invoke(slot);
        }
    }
}
