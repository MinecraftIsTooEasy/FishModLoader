package net.xiaoyu233.fml.forge.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.Packet250CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.xiaoyu233.fml.FishModLoader;

/**
 * Helper for sending {@code Packet250CustomPayload} packets the way Forge
 * 1.6.4 mods expect. This is the small, hand-written counterpart to
 * Forge's {@code PacketDispatcher} class — we don't ship cpw's full
 * version because most of its surface is tied to the FML network manager
 * which we shim differently.
 */
public final class PacketDispatcher {

    private PacketDispatcher() {}

    public static Packet250CustomPayload makePacket(String channel, byte[] payload) {
        Packet250CustomPayload packet = new Packet250CustomPayload();
        packet.channel = channel;
        packet.data = payload;
        packet.length = payload == null ? 0 : payload.length;
        return packet;
    }

    /** Send {@code packet} to a single server-side player. */
    public static void sendPacketToPlayer(Packet packet, EntityPlayerMP player) {
        if (player != null && player.playerNetServerHandler != null) {
            player.playerNetServerHandler.sendPacketToPlayer(packet);
        }
    }

    /** Broadcast {@code packet} to every connected player. */
    public static void sendPacketToAllPlayers(Packet packet) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;
        ServerConfigurationManager mgr = server.getConfigurationManager();
        if (mgr != null) {
            mgr.sendPacketToAllPlayers(packet);
        }
    }

    /** Send {@code packet} from a client to the server. */
    public static void sendPacketToServer(Packet packet) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc != null && mc.getNetHandler() != null) {
                mc.getNetHandler().addToSendQueue(packet);
            }
        } catch (Throwable thrown) {
            FishModLoader.LOGGER.warn("sendPacketToServer failed (no client/net handler)", thrown);
        }
    }
}
