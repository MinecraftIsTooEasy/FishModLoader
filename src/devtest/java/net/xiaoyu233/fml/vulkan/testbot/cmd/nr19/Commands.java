package net.xiaoyu233.fml.vulkan.testbot.cmd.nr19;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.ContainerEnchantment;
import net.minecraft.Enchantment;
import net.minecraft.EntityHorse;
import net.minecraft.EntityPlayer;
import net.minecraft.GuiAchievements;
import net.minecraft.GuiBeacon;
import net.minecraft.GuiChest;
import net.minecraft.GuiContainer;
import net.minecraft.GuiContainerCreative;
import net.minecraft.GuiCrafting;
import net.minecraft.GuiEnchantment;
import net.minecraft.GuiInventory;
import net.minecraft.GuiMerchant;
import net.minecraft.GuiRepair;
import net.minecraft.GuiScreen;
import net.minecraft.GuiScreenHorseInventory;
import net.minecraft.IMerchant;
import net.minecraft.InventoryBasic;
import net.minecraft.Item;
import net.minecraft.ItemStack;
import net.minecraft.Minecraft;
import net.minecraft.MerchantRecipe;
import net.minecraft.MerchantRecipeList;
import net.minecraft.ScaledResolution;
import net.minecraft.Slot;
import net.minecraft.TileEntityBeacon;
import net.minecraft.World;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.testbot.Frozen;
import net.xiaoyu233.fml.vulkan.testbot.ImageStats;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.Stage;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;
import net.xiaoyu233.fml.vulkan.window.InputManager;

/**
 * Devtest commands of work package NR-19 (complex screens): {@code containerScreens}.
 */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-19";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new ContainerScreens());
    }

    /** Every container screen the command knows, in capture order. */
    static final List<String> SCREENS = List.of("inventory", "chest", "chestcarry", "crafting", "creative", "enchantment", "merchant", "anvil", "beacon",
        "horse", "achievements");

    /** Screens that show a 3D model, an enchanting book or item lighting: they get a second frame (T1) at another partial tick. */
    static final Set<String> MODEL_SCREENS = Set.of("inventory", "enchantment", "horse", "creative");

    /** Partial tick of the T0 frame and of the T1 frame of the model screens. */
    static final float T0_PARTIAL = 0.5F;
    static final float T1_PARTIAL = 0.25F;

    /** Default output when {@code -Dfml.vulkan.testbot.docs} is not set (runClient -Ptestbot sets it to the docs directory next to the repo). */
    static final String DEFAULT_DOCS = "docs";

    /**
     * {@code containerScreens [screen ...]}: opens each container screen on the player of the museum world, freezes the client clock
     * ({@link Frozen#beginCapture}) and saves the frame as {@code nr19_<screen>_T0.png}; model screens also get
     * {@code nr19_<screen>_T1.png} (partial tick {@value #T1_PARTIAL}). Files are written to the capture directory by the bot and copied to
     * {@code <docs>/screenshots/nr19/}, together with {@code scenes-nr19.tsv} (id, tolerance class, partial tick, frozen state).
     * Without arguments every screen is captured; unknown names fail the section.
     */
    static final class ContainerScreens implements DevCommand {
        @Override
        public String name() {
            return "containerScreens";
        }

        @Override
        public String description() {
            return "open every container screen (inventory, chest, chestcarry, crafting, creative, enchanting, merchant, anvil, beacon, horse, "
                + "achievements) and take frozen T0/T1 captures to docs/screenshots/nr19 (args: screen names, default all)";
        }

        @Override
        public void run(Report.Section section, String[] args) throws Exception {
            TestBot bot = TestBot.get();
            if (bot == null) {
                section.fail("containerScreens", "the test bot is not running");
                return;
            }
            List<String> screens = new ArrayList<>();
            if (args.length == 0) {
                screens.addAll(SCREENS);
            } else {
                for (String a : args) {
                    String key = a.toLowerCase(Locale.ROOT);
                    if (!SCREENS.contains(key)) {
                        section.fail("containerScreens", "unknown screen '" + a + "' (known: " + String.join(", ", SCREENS) + ")");
                        return;
                    }
                    screens.add(key);
                }
            }
            Stage stage = bot.stage();
            if (stage == null) {
                section.fail("containerScreens", "no scripted world (Stage not published)");
                return;
            }
            Minecraft mc = bot.minecraft();
            boolean inWorld = bot.client(() -> mc.theWorld != null && mc.thePlayer != null);
            if (!inWorld) {
                section.fail("containerScreens", "no world: the containers need the museum world");
                return;
            }
            if (!Frozen.active()) {
                section.warn("frozen mode", "captures run without -Pvk.frozen=true: they are not reproducible");
            }
            Path docs = Path.of(System.getProperty("fml.vulkan.testbot.docs", DEFAULT_DOCS));
            Path outDir = docs.resolve("screenshots").resolve("nr19");
            Files.createDirectories(outDir);

            // same set-up as the world scenes of the capture runner: creative flying player at the museum, empty inventory and armour
            stage.prepPlayer();
            double[] centre = stage.ensureMuseum();
            stage.place(centre[0], centre[1], centre[2] - 8, 0F, 12F);
            bot.server(() -> {
                net.minecraft.ServerPlayer p = (net.minecraft.ServerPlayer) net.minecraft.server.MinecraftServer.getServer()
                    .getConfigurationManager().playerEntityList.get(0);
                Arrays.fill(p.inventory.mainInventory, null);
                Arrays.fill(p.inventory.armorInventory, null);
                p.inventory.currentItem = 0;
                p.inventoryContainer.detectAndSendChanges();
            });
            bot.client(() -> {
                Arrays.fill(mc.thePlayer.inventory.mainInventory, null);
                Arrays.fill(mc.thePlayer.inventory.armorInventory, null);
                mc.thePlayer.inventory.currentItem = 0;
            });
            bot.ticks(5);
            if (Frozen.active()) {
                int n = bot.client(() -> Frozen.freezeAtlas(mc));
                section.info("atlas", "animated sprites brought to frame 0 (" + n + " animation steps) and frozen for the route");
            }
            ClientState saved = bot.client(() -> new ClientState(mc.thePlayer.experience, mc.thePlayer.capabilities.isCreativeMode));

            List<String> manifest = new ArrayList<>();
            manifest.add("id\ttolerance\tpartial\tfile\tstate");
            int shots = 0;
            for (String screen : screens) {
                List<float[]> frames = new ArrayList<>();
                frames.add(new float[] {T0_PARTIAL});
                if (MODEL_SCREENS.contains(screen)) {
                    frames.add(new float[] {T1_PARTIAL});
                }
                for (float[] frame : frames) {
                    boolean t1 = frame[0] != T0_PARTIAL;
                    String tol = t1 ? "T1" : "T0";
                    String base = "nr19_" + screen + "_" + tol;
                    String[] state = new String[1];
                    String path = capture(bot, stage, mc, section, screen, base, frame[0], state, saved);
                    if (path == null) {
                        continue;
                    }
                    Files.copy(Path.of(path), outDir.resolve(base + ".png"), StandardCopyOption.REPLACE_EXISTING);
                    manifest.add(screen + "\t" + tol + "\t" + frame[0] + "\t" + base + ".png\t" + state[0]);
                    shots++;
                    section.ok("container " + screen + " " + tol);
                }
            }
            bot.client(() -> mc.displayGuiScreen(null));
            Files.write(outDir.resolve("scenes-nr19.tsv"), manifest, StandardCharsets.UTF_8);
            section.info("screens", shots + " capture(s) in " + outDir + " (" + screens.size() + " screen(s), manifest scenes-nr19.tsv)");
            stage.prepPlayer();
        }

        /**
         * Opens one screen, waits for a quiet renderer, sets its scene (contents, hover, carried stack), freezes the clock and saves the frame.
         * Returns the bot's file or null.
         */
        private static String capture(TestBot bot, Stage stage, Minecraft mc, Report.Section s, String screen, String base, float partial,
            String[] stateOut, ClientState saved) throws Exception {
            bot.status("capture container " + screen + " " + base);
            bot.client(() -> mc.displayGuiScreen(null));
            bot.frames(2);
            String glBefore = bot.client(() -> ContainerScreens.glState());
            bot.client(() -> mc.displayGuiScreen(create(screen, mc)));
            bot.frames(10);
            if (!stage.quietFrames(20, 1500)) {
                s.warn("container " + screen, "renderer did not reach 20 quiet frames within 1500 frames; captured anyway");
            }
            bot.client(() -> {
                scene(screen, mc);
                if (mc.ingameGUI != null) {
                    // chat lines fade with the real GUI counter: they must not show in a capture
                    mc.ingameGUI.getChatGUI().clearChatMessages();
                }
                Frozen.pinClientCounters(mc);
                Frozen.beginCapture(partial);
            });
            bot.frames(3);
            // the first frame of the window may still use the real partial tick: pin again
            bot.client(() -> Frozen.pinClientCounters(mc));
            bot.frames(3);
            String state = bot.client(() -> Frozen.fingerprint(mc));
            stateOut[0] = state;
            String path = bot.screenshot(s, base);
            String after = bot.client(() -> Frozen.fingerprint(mc));
            s.info("state " + base, state + (after.equals(state) ? "" : "   | AFTER: " + after));
            bot.client(Frozen::endCapture);
            bot.client(() -> {
                mc.displayGuiScreen(null);
                restoreClient(mc, saved);
            });
            bot.frames(2);
            // drawScreen must leave lighting and depth as it found them (the item and tooltip blocks toggle them)
            String glAfter = bot.client(() -> ContainerScreens.glState());
            if (!glAfter.equals(glBefore)) {
                s.warn("container " + screen, "GL state after the screen differs from before: " + glBefore + " -> " + glAfter);
            }
            if (path == null) {
                s.fail("container " + screen, "no capture file for " + base);
                return null;
            }
            double luma = ImageStats.meanLuma(ImageStats.read(path));
            if (luma < 1.0) {
                s.warn("container " + screen, "capture is black (mean luma " + String.format(Locale.ROOT, "%.2f", luma) + ")");
            }
            return path;
        }

        /** The screen object of a name; called on the client thread with the museum player in the world. */
        static GuiScreen create(String screen, Minecraft mc) {
            EntityPlayer p = mc.thePlayer;
            World w = mc.theWorld;
            return switch (screen) {
                case "inventory" -> new GuiInventory(p);
                case "chest", "chestcarry" -> new GuiChest(p, testChest());
                case "crafting" -> new GuiCrafting(p, w, 0, 0, 0);
                case "creative" -> new GuiContainerCreative(p);
                case "enchantment" -> new GuiEnchantment(p, w, 0, 0, 0, "Test");
                case "merchant" -> new GuiMerchant(p, new TestMerchant(p), "Test merchant");
                case "anvil" -> new GuiRepair(p, 0, 0, 0);
                case "beacon" -> new GuiBeacon(p, new TileEntityBeacon());
                case "horse" -> {
                    EntityHorse horse = new EntityHorse(w);
                    yield new GuiScreenHorseInventory(p, new InventoryBasic("Test horse", false, 2), horse);
                }
                case "achievements" -> new GuiAchievements(mc.statFileWriter);
                default -> throw new IllegalArgumentException("unknown screen '" + screen + "'");
            };
        }

        /** A chest with a stack count, a damaged item and an enchanted item (slots 0, 1, 2). */
        static InventoryBasic testChest() {
            InventoryBasic chest = new InventoryBasic("Test chest", false, 27);
            chest.setInventorySlotContents(0, new ItemStack(Item.diamond, 16));
            ItemStack damaged = new ItemStack(Item.pickaxeIron);
            damaged.setItemDamage(40);
            chest.setInventorySlotContents(1, damaged);
            ItemStack enchanted = new ItemStack(Item.swordGold);
            enchanted.addEnchantment(Enchantment.sharpness, 3);
            chest.setInventorySlotContents(2, enchanted);
            return chest;
        }

        /**
         * The per-screen scene, set on the client thread after the screen is open: enchanting levels and a non-creative player (enabled,
         * disabled and hovered options), a hovered slot (highlight and tooltip), a carried stack (chestcarry: the tooltip is suppressed).
         * The mouse is always placed (parked in the corner for the other screens) so the captures do not depend on the real cursor.
         */
        static void scene(String screen, Minecraft mc) {
            GuiScreen gs = mc.currentScreen;
            switch (screen) {
                case "enchantment" -> {
                    ContainerEnchantment ce = (ContainerEnchantment) ((GuiContainer) gs).inventorySlots;
                    ce.enchantLevels[0] = 1; // 100 xp: affordable
                    ce.enchantLevels[1] = 2; // 200 xp: affordable
                    ce.enchantLevels[2] = 30; // 3000 xp: unaffordable, drawn disabled
                    // creative players may always enchant: the disabled option is only drawn in survival
                    mc.thePlayer.capabilities.isCreativeMode = false;
                    mc.thePlayer.experience = 500;
                    hoverAt(mc, gs, 60 + 10, 14 + 10); // option 0 (enabled), hovered
                }
                case "chest" -> hoverSlot(mc, gs, 2); // enchanted sword: highlight and tooltip
                case "chestcarry" -> {
                    mc.thePlayer.inventory.setItemStack(new ItemStack(Item.diamond, 8));
                    hoverSlot(mc, gs, 0); // carried stack drawn at the cursor, tooltip suppressed
                }
                default -> parkMouse(mc);
            }
        }

        /** The hover point of a slot (the centre of its 16x16 area) on the screen. */
        static void hoverSlot(Minecraft mc, GuiScreen gs, int index) {
            Slot slot = ((GuiContainer) gs).inventorySlots.getSlot(index);
            hoverAt(mc, gs, slot.xDisplayPosition + 8, slot.yDisplayPosition + 8);
        }

        /** Places the mouse at a point relative to the top-left of the container. */
        static void hoverAt(Minecraft mc, GuiScreen gs, int relX, int relY) {
            int left = (Integer) Frozen.getField(gs, "guiLeft");
            int top = (Integer) Frozen.getField(gs, "guiTop");
            mouseAt(mc, left + relX, top + relY);
        }

        static void parkMouse(Minecraft mc) {
            mouseAt(mc, 0, 0);
        }

        /**
         * Places the input mouse at a point of the scaled GUI, as the frame loop reads it back ({@code EntityRenderer}: the scaled point of
         * the mouse position is the GUI coordinate). The position is written to the input manager directly: {@code setCursorPosition} would
         * warp the real cursor, which the bot must never do.
         */
        static void mouseAt(Minecraft mc, int guiX, int guiY) {
            ScaledResolution sr = new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
            int sw = sr.getScaledWidth();
            int sh = sr.getScaledHeight();
            int px = (int) ((guiX + 0.5) * mc.displayWidth / sw);
            int py = (int) ((sh - 1 - guiY + 0.5) * mc.displayHeight / sh);
            try {
                Field fx = InputManager.class.getDeclaredField("mouseX");
                fx.setAccessible(true);
                fx.setInt(null, px);
                Field fy = InputManager.class.getDeclaredField("mouseY");
                fy.setAccessible(true);
                fy.setInt(null, py);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot place the bot's mouse: " + e, e);
            }
        }

        /** Client state the scenes change; put back after every capture. */
        static void restoreClient(Minecraft mc, ClientState saved) {
            mc.thePlayer.experience = saved.experience();
            mc.thePlayer.capabilities.isCreativeMode = saved.creative();
            mc.thePlayer.inventory.setItemStack(null);
        }

        /** The GL state the container drawing toggles (lighting and depth test), for the before/after check. */
        static String glState() {
            return "lighting=" + Draw.lightingEnabled() + " depth=" + Draw.depthTestEnabled();
        }
    }

    /** The client state the container scenes change (experience, creative flag). */
    record ClientState(int experience, boolean creative) {
    }

    /** A merchant with one trade (two iron ingots and an emerald for an apple): the merchant screen draws the item and lighting block. */
    static final class TestMerchant implements IMerchant {
        private final EntityPlayer customer;
        private MerchantRecipeList recipes = new MerchantRecipeList();

        TestMerchant(EntityPlayer customer) {
            this.customer = customer;
            this.recipes.add(new MerchantRecipe(new ItemStack(Item.ingotIron, 2), new ItemStack(Item.emerald, 1), new ItemStack(Item.appleRed, 1)));
        }

        @Override
        public void setCustomer(EntityPlayer player) {
        }

        @Override
        public EntityPlayer getCustomer() {
            return customer;
        }

        @Override
        public MerchantRecipeList getRecipes(EntityPlayer player) {
            return recipes;
        }

        @Override
        public void setRecipes(MerchantRecipeList list) {
            this.recipes = list;
        }

        @Override
        public void useRecipe(MerchantRecipe recipe) {
        }

        @Override
        public void func_110297_a_(ItemStack stack) {
        }
    }
}
