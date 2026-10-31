package net.xiaoyu233.fml.vulkan.testbot.cmd.nr14;

import net.minecraft.EntityItem;
import net.minecraft.GuiInventory;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * {@code itemCaptures [kind...]}: for every kind (block, sprite, enchanted, map, bow, potion; default all) on a frozen scene captures the
 * hotbar icon, the inventory GUI icon, the held item in first person and the item dropped in the world.
 */
final class ItemCapturesCommand implements DevCommand {
    @Override
    public String name() {
        return "itemCaptures";
    }

    @Override
    public String description() {
        return "frozen scene captures of hotbar/inventory icons, held item and dropped item for block, sprite, enchanted, map, bow, potion";
    }

    @Override
    public void run(Report.Section section, String[] args) throws Exception {
        TestBot bot = TestBot.get();
        String[] kinds = args.length > 0 ? args : Scene.KINDS;
        for (String kind : kinds) {
            Scene.stack(kind); // validates the name
        }
        Scene.stage(bot);
        for (String kind : kinds) {
            Scene.give(bot, 0, kind);
            bot.stage().look(0.0F, 12.0F);
            bot.ticks(12);
            Scene.beginFrozen(bot);
            try {
                bot.screenshot(section, "nr14_" + kind + "_hotbar_held");
                bot.client(() -> bot.minecraft().displayGuiScreen(new GuiInventory(bot.minecraft().thePlayer)));
                bot.frames(10);
                bot.screenshot(section, "nr14_" + kind + "_inventory");
                bot.client(() -> bot.minecraft().displayGuiScreen(null));
                bot.frames(5);
                EntityItem dropped = Scene.drop(bot, kind);
                bot.frames(5);
                bot.screenshot(section, "nr14_" + kind + "_dropped");
                bot.client(() -> dropped.setDead());
            } finally {
                Scene.endFrozen(bot);
            }
            section.info("itemCaptures", kind + " captured");
        }
    }
}
