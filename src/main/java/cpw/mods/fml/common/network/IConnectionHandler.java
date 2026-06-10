package cpw.mods.fml.common.network;

import net.minecraft.network.INetworkManager;
import net.minecraft.network.packet.NetHandler;
import net.minecraft.network.packet.Packet1Login;

/**
 * Stub for cpw.mods.fml.common.network.IConnectionHandler (Forge 1.6.4).
 * Lifecycle hooks for network connections.
 */
public interface IConnectionHandler {
    void playerLoggedIn(Player player, NetHandler netHandler, INetworkManager manager);
    String connectionReceived(net.minecraft.network.NetLoginHandler netHandler, INetworkManager manager);
    void connectionOpened(NetHandler netClientHandler, String server, int port, INetworkManager manager);
    void connectionOpened(NetHandler netClientHandler, net.minecraft.server.MinecraftServer server, INetworkManager manager);
    void connectionClosed(INetworkManager manager);
    void clientLoggedIn(NetHandler clientHandler, INetworkManager manager, Packet1Login login);
}
