package cpw.mods.fml.common.network;

import net.minecraft.network.packet.NetHandler;
import net.minecraft.network.packet.Packet131MapData;

/**
 * Stub for cpw.mods.fml.common.network.ITinyPacketHandler (Forge 1.6.4).
 * Forge 1.6.4 used Packet131MapData for tiny custom packets.
 */
public interface ITinyPacketHandler {

    void handle(NetHandler handler, Packet131MapData mapData);
}
