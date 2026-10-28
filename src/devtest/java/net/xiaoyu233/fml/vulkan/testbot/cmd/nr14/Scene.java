package net.xiaoyu233.fml.vulkan.testbot.cmd.nr14;

import net.minecraft.Block;
import net.minecraft.Enchantment;
import net.minecraft.EntityItem;
import net.minecraft.Item;
import net.minecraft.ItemStack;
import net.minecraft.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.ServerPlayer;
import net.xiaoyu233.fml.vulkan.testbot.Frozen;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;

/** Shared scene helpers of the NR-14 commands: item kinds, giving a stack to both sides of the player, a frozen platform pose. */
final class Scene {
    static final String[] KINDS = {"block", "sprite", "enchanted", "map", "bow", "potion"};

    private Scene() {
    }

    static ItemStack stack(String kind) {
        switch (kind) {
            case "block":
                return new ItemStack(Block.stone.blockID, 1, 0);
            case "sprite":
                return new ItemStack(Item.diamond, 1);
            case "enchanted": {
                ItemStack s = new ItemStack(Item.swordIron, 1);
                s.addEnchantment(Enchantment.sharpness, 1);
                return s;
            }
            case "map":
                return new ItemStack(Item.map, 1, 0);
            case "bow":
                return new ItemStack(Item.bow, 1);
            case "potion":
                return new ItemStack(Item.potion, 1, 8197);
            default:
                throw new IllegalArgumentException("unknown kind " + kind + ", expected one of " + String.join(",", KINDS));
        }
    }

    /** Puts {@code kind} into hotbar slot {@code slot} (client and server copy) and selects it. */
    static void give(TestBot bot, int slot, String kind) throws Exception {
        bot.server(() -> {
            ServerPlayer p = (ServerPlayer) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
            p.inventory.mainInventory[slot] = stack(kind);
            p.inventory.currentItem = slot;
            p.inventoryContainer.detectAndSendChanges();
        });
        bot.client(() -> {
            Minecraft mc = bot.minecraft();
            mc.thePlayer.inventory.mainInventory[slot] = stack(kind);
            mc.thePlayer.inventory.currentItem = slot;
        });
    }

    static void select(TestBot bot, int slot) throws Exception {
        bot.server(() -> ((ServerPlayer) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0)).inventory.currentItem = slot);
        bot.client(() -> bot.minecraft().thePlayer.inventory.currentItem = slot);
    }

    /** Places the player on the museum platform looking at it, ready to capture. */
    static void stage(TestBot bot) throws Exception {
        bot.stage().prepPlayer();
        double[] c = bot.stage().ensureMuseum();
        bot.stage().place(c[0], c[1] + 1.0, c[2] - 8.0, 0.0F, 12.0F);
        bot.stage().settle(20, 20, 400);
    }

    static void beginFrozen(TestBot bot) throws Exception {
        bot.client(() -> Frozen.beginCapture(Frozen.DEFAULT_PARTIAL_TICKS));
    }

    static void endFrozen(TestBot bot) throws Exception {
        bot.client(Frozen::endCapture);
    }

    /** Spawns a frozen client side dropped item two blocks in front of the player. */
    static EntityItem drop(TestBot bot, String kind) throws Exception {
        return bot.client(() -> {
            Minecraft mc = bot.minecraft();
            double x = mc.thePlayer.posX;
            double z = mc.thePlayer.posZ + 2.0;
            EntityItem e = new EntityItem(mc.theWorld, x, mc.thePlayer.posY - 0.6, z, stack(kind));
            e.age = 0;
            mc.theWorld.spawnEntityInWorld(e);
            Frozen.freezeEntity(e);
            return e;
        });
    }
}
