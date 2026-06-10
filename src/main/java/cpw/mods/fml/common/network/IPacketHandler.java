package cpw.mods.fml.common.network;

import net.minecraft.network.INetworkManager;
import net.minecraft.network.packet.Packet250CustomPayload;

/**
 * Stub for cpw.mods.fml.common.network.IPacketHandler (Forge 1.6.4).
 *
 * Implementors handle custom-payload packets routed by FMLNetworkHandler.
 * Stage-6 (system-level network) will wire actual delivery.
 */
public interface IPacketHandler {
    void onPacketData(INetworkManager manager,
                      Packet250CustomPayload packet,
                      Player player);
}
