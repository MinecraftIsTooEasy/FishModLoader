package net.xiaoyu233.fml.vulkan.testbot.cmd.nr25;

import java.util.List;
import java.util.function.Function;
import net.minecraft.EntityPlayer;
import net.minecraft.GuiBrewingStand;
import net.minecraft.GuiChat;
import net.minecraft.GuiChest;
import net.minecraft.GuiConfirmOpenLink;
import net.minecraft.GuiCrafting;
import net.minecraft.GuiDispenser;
import net.minecraft.GuiFurnace;
import net.minecraft.GuiGameOver;
import net.minecraft.GuiHopper;
import net.minecraft.GuiScreen;
import net.minecraft.GuiScreenBook;
import net.minecraft.GuiScreenDemo;
import net.minecraft.InventoryBasic;
import net.minecraft.Item;
import net.minecraft.ItemStack;
import net.minecraft.Minecraft;
import net.minecraft.TileEntityBrewingStand;
import net.minecraft.TileEntityDispenser;
import net.minecraft.TileEntityFurnace;
import net.minecraft.TileEntityHopper;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/**
 * Devtest commands of work package NR-25: one command per simple GUI screen ({@code guiFurnace}, {@code guiChest}, {@code guiDispenser},
 * {@code guiHopper}, {@code guiBrewingStand}, {@code guiCrafting}, {@code guiGameOver}, {@code guiBook}, {@code guiChat},
 * {@code guiConfirmLink}, {@code guiDemo}). Each opens its screen deterministically in the current world; the capture scenes of
 * {@code native-captures/NR-25.txt} run them as {@code cmd} scenes, and the capture runner closes the screen after the capture.
 */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-25";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(
            new OpenScreen("guiFurnace", "open GuiFurnace with an empty furnace (the player inventory below)",
                mc -> new GuiFurnace(mc.thePlayer, new TileEntityFurnace())),
            new OpenScreen("guiChest", "open GuiChest with an empty 27 slot chest inventory",
                mc -> new GuiChest(mc.thePlayer, new InventoryBasic("container.chest", false, 27))),
            new OpenScreen("guiDispenser", "open GuiDispenser with an empty dispenser",
                mc -> new GuiDispenser(mc.thePlayer, new TileEntityDispenser())),
            new OpenScreen("guiHopper", "open GuiHopper with an empty hopper",
                mc -> new GuiHopper(mc.thePlayer, new TileEntityHopper())),
            new OpenScreen("guiBrewingStand", "open GuiBrewingStand with an empty brewing stand",
                mc -> new GuiBrewingStand(mc.thePlayer, new TileEntityBrewingStand())),
            new OpenScreen("guiCrafting", "open GuiCrafting (crafting table at the origin of the world)",
                mc -> new GuiCrafting(mc.thePlayer, mc.theWorld, 0, 0, 0)),
            new OpenScreen("guiGameOver", "open GuiGameOver (death screen, translucent over the world)",
                mc -> new GuiGameOver()),
            new OpenScreen("guiBook", "open GuiScreenBook on a new writable book (one blank page)",
                mc -> new GuiScreenBook(mc.thePlayer, new ItemStack(Item.writableBook), true)),
            new OpenScreen("guiChat", "open GuiChat with an empty input line (translucent over the world)",
                mc -> new GuiChat()),
            new OpenScreen("guiConfirmLink", "open GuiConfirmOpenLink for a fixed link (translucent over the world)",
                mc -> new GuiConfirmOpenLink(null, "https://minecraft.net/", 0, false)),
            new OpenScreen("guiDemo", "open GuiScreenDemo (demo screen, translucent over the world)",
                mc -> new GuiScreenDemo()));
    }

    /** Opens one screen on the client thread in the current world. Args: none. */
    private static final class OpenScreen implements DevCommand {
        private final String name;
        private final String description;
        private final Function<Minecraft, GuiScreen> factory;

        OpenScreen(String name, String description, Function<Minecraft, GuiScreen> factory) {
            this.name = name;
            this.description = description;
            this.factory = factory;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            String[] opened = new String[1];
            TestBot.get().client(() -> {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc.thePlayer == null || mc.theWorld == null) {
                    opened[0] = null;
                    return null;
                }
                GuiScreen screen = factory.apply(mc);
                mc.displayGuiScreen(screen);
                opened[0] = screen.getClass().getSimpleName();
                return null;
            });
            if (opened[0] == null) {
                s.fail(name, "no world and player: the screen cannot be opened");
                return;
            }
            s.info(name, "opened " + opened[0]);
        }
    }
}
