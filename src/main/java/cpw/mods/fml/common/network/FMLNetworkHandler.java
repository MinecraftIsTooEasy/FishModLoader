package cpw.mods.fml.common.network;

import net.minecraft.network.packet.Packet250CustomPayload;

/**
 * Stub for cpw.mods.fml.common.network.FMLNetworkHandler (Forge 1.6.4).
 *
 * Currently returns null/no-op. Stage 6 will route packets through MITE's
 * network stack.
 */
public class FMLNetworkHandler {

    public static Packet250CustomPayload getFMLFakePacket() {
        return null;
    }

    public static void openGui(net.minecraft.entity.player.EntityPlayer player, Object mod, int modGuiId,
                               net.minecraft.world.World world, int x, int y, int z) {
        // TODO: stage 6 — route through IGuiHandler registry
    }

    public static int getEntitySpawnId(net.minecraft.entity.Entity entity) {
        return entity.entityId;
    }

    public static int getModEntityId(Class<?> entityClass) {
        return 0;
    }
}
