package net.xiaoyu233.fml.vulkan.testbot.cmd.nr14;

import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.minecraft.Item;
import net.minecraft.ItemStack;

/** {@code handSweep}: first person hand states: idle, swing at several progress points, eating, bow draw and the equip animation after a slot change. */
final class HandSweepCommand implements DevCommand {
    @Override
    public String name() {
        return "handSweep";
    }

    @Override
    public String description() {
        return "first person hand: idle, swing (3 phases), eat, bow draw, equip animation";
    }

    @Override
    public void run(Report.Section section, String[] args) throws Exception {
        TestBot bot = TestBot.get();
        Scene.stage(bot);
        Scene.give(bot, 0, "sprite");
        Scene.give(bot, 1, "bow");
        bot.server(() -> {
            var p = (net.minecraft.ServerPlayer) net.minecraft.server.MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
            p.inventory.mainInventory[2] = new ItemStack(Item.appleRed, 4);
            p.inventory.mainInventory[3] = new ItemStack(Item.arrowFlint, 8);
            p.inventoryContainer.detectAndSendChanges();
        });
        bot.client(() -> {
            var inv = bot.minecraft().thePlayer.inventory;
            inv.mainInventory[2] = new ItemStack(Item.appleRed, 4);
            inv.mainInventory[3] = new ItemStack(Item.arrowFlint, 8);
        });
        Scene.select(bot, 0);
        bot.stage().look(0.0F, 12.0F);
        bot.ticks(20);
        bot.screenshot(section, "nr14_hand_idle");

        bot.client(() -> bot.minecraft().thePlayer.swingArm());
        for (int phase = 1; phase <= 3; phase++) {
            bot.ticks(2);
            bot.screenshot(section, "nr14_hand_swing" + phase);
        }
        bot.ticks(10);

        Scene.select(bot, 2);
        bot.ticks(20);
        boolean eating = bot.client(() -> {
            var p = bot.minecraft().thePlayer;
            p.setHeldItemInUse();
            return p.isUsingItem();
        });
        section.info("handSweep", "eat started: " + eating);
        bot.ticks(6);
        bot.screenshot(section, "nr14_hand_eat");
        bot.client(() -> bot.minecraft().thePlayer.clearItemInUse());

        Scene.select(bot, 1);
        bot.ticks(1);
        bot.screenshot(section, "nr14_hand_equip_early");
        bot.ticks(20);
        boolean drawing = bot.client(() -> {
            var p = bot.minecraft().thePlayer;
            p.setHeldItemInUse();
            return p.isUsingItem();
        });
        section.info("handSweep", "bow draw started: " + drawing);
        bot.ticks(12);
        bot.screenshot(section, "nr14_hand_bow_draw");
        bot.client(() -> bot.minecraft().thePlayer.clearItemInUse());
        Scene.select(bot, 0);
        bot.ticks(20);
    }
}
