package vkbench;

import java.util.List;
import net.minecraft.Block;
import net.minecraft.Damage;
import net.minecraft.DamageSource;
import net.minecraft.Entity;
import net.minecraft.EntityItem;
import net.minecraft.EntityList;
import net.minecraft.EntityLivingBase;
import net.minecraft.TileEntityChest;
import net.minecraft.WorldServer;

/**
 * Dev only (Twilight Forest report): every TF boss is spawned on a stone floor in the overworld and killed by fire damage alone (no
 * player hit), then the chests and dropped items around it are logged. Active with {@code -Dfml.vulkan.bossFireScene=true}.
 */
final class BossFireScene {
    private static final String[] BOSSES = System.getProperty("fml.vulkan.bossFireScene.only", "naga,twilight_lich,minoshroom,hydra,knight_phantom,tower_boss,yeti_boss,snow_queen").split(",");
    private final Bench bench;

    BossFireScene(Bench bench) {
        this.bench = bench;
    }

    void run() throws Exception {
        if (!bench.waitForCondition(bench::mainMenuUp, 6000)) {
            Bench.log("bossfire: main menu not reached");
            return;
        }
        bench.createWorld(false, "vkbossfire");
        if (!bench.waitForCondition(() -> bench.mc.theWorld != null && bench.mc.thePlayer != null && bench.mc.currentScreen == null, 6000)) {
            Bench.log("bossfire: world not entered");
            return;
        }
        bench.waitTicks(40);
        for (String boss : BOSSES) {
            final String name = "twilightforest:" + boss;
            int[] base = bench.server(() -> {
                WorldServer w = bench.serverWorld(0);
                int bx = (int) bench.serverPlayer().posX + 40;
                int bz = (int) bench.serverPlayer().posZ;
                int by = 150;
                for (int x = bx - 12; x <= bx + 12; x++) {
                    for (int z = bz - 12; z <= bz + 12; z++) {
                        for (int y = by - 30; y <= by + 14; y++) {
                            w.setBlock(x, y, z, y == by - 1 ? Block.stone.blockID : 0, 0, 2);
                        }
                    }
                }
                return new int[] {bx, by, bz};
            });
            bench.server(() -> bench.serverPlayer().playerNetServerHandler.setPlayerLocation(base[0] - 20.5, base[1], base[2] + 0.5, -90, 0));
            bench.waitTicks(20);
            String state = bench.server(() -> {
                WorldServer w = bench.serverWorld(0);
                Entity e = EntityList.createEntityByName(name, w);
                if (!(e instanceof EntityLivingBase l)) {
                    return "cannot create " + name;
                }
                e.setLocationAndAngles(base[0] + 0.5, base[1], base[2] + 0.5, 0, 0);
                w.spawnEntityInWorld(e);
                String spot;
                try {
                    java.lang.reflect.Method m = Class.forName("twilightforest.TFBossLoot").getDeclaredMethod("findSpot", net.minecraft.World.class, Entity.class, boolean.class);
                    m.setAccessible(true);
                    int[] r = (int[]) m.invoke(null, w, e, false);
                    spot = r == null ? "none" : r[0] + "," + r[1] + "," + r[2];
                } catch (ReflectiveOperationException ex) {
                    spot = "reflect " + ex;
                }
                Bench.log("bossfire: " + name + " spawned at " + e.posX + "/" + e.posY + "/" + e.posZ + " chest spot before death " + spot);
                for (int k = 0; k < 400 && !l.isDead && l.getHealth() > 0.0F; k++) {
                    l.setFire(10);
                    l.hurtResistantTime = 0;
                    l.attackEntityFrom(new Damage(DamageSource.onFire, 50.0F));
                }
                return e.getClass().getSimpleName() + " dead=" + l.isDead + " health=" + l.getHealth() + " died at " + e.posX + "/" + e.posY + "/" + e.posZ;
            });
            bench.waitTicks(260);
            String found = bench.server(() -> {
                WorldServer w = bench.serverWorld(0);
                int chests = 0;
                int stacks = 0;
                for (int x = base[0] - 12; x <= base[0] + 12; x++) {
                    for (int z = base[2] - 12; z <= base[2] + 12; z++) {
                        for (int y = base[1] - 30; y <= base[1] + 14; y++) {
                            if (w.getBlockTileEntity(x, y, z) instanceof TileEntityChest c) {
                                chests++;
                                for (int s = 0; s < c.getSizeInventory(); s++) {
                                    if (c.getStackInSlot(s) != null) {
                                        stacks++;
                                    }
                                }
                            }
                        }
                    }
                }
                List<?> items = w.getEntitiesWithinAABB(EntityItem.class, net.minecraft.AxisAlignedBB.getBoundingBox(base[0] - 16, base[1] - 32,
                        base[2] - 16, base[0] + 16, base[1] + 20, base[2] + 16));
                StringBuilder names = new StringBuilder();
                for (Object o : items) {
                    EntityItem it = (EntityItem) o;
                    names.append(it.getEntityItem().getDisplayName()).append('x').append(it.getEntityItem().stackSize)
                            .append(String.format("@%.0f,%.0f,%.0f ", it.posX, it.posY, it.posZ));
                }
                for (Object o : w.loadedEntityList.toArray()) {
                    if (!(o instanceof net.minecraft.EntityPlayer)) {
                        ((Entity) o).setDead();
                    }
                }
                return "chests " + chests + " (" + stacks + " stacks), items on ground " + items.size() + " " + names;
            });
            Bench.log("bossfire: " + boss + ": " + state + " -> " + found);
        }
        Bench.log("bossfire: done");
    }
}
