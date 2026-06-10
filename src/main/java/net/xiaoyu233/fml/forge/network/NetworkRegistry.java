package net.xiaoyu233.fml.forge.network;

import cpw.mods.fml.common.network.IPacketHandler;
import net.xiaoyu233.fml.FishModLoader;

import java.util.HashMap;
import java.util.Map;

/**
 * Channel → {@link IPacketHandler} registry used by the Forge 1.6.4
 * compatibility layer.
 *
 * <p>Forge mods register a channel (string up to 20 chars) and a handler;
 * incoming {@code Packet250CustomPayload} with that channel name is
 * routed to the handler by the inject hooks on
 * {@code NetClientHandler.handleCustomPayload} and
 * {@code NetServerHandler.handleCustomPayload}.
 */
public final class NetworkRegistry {

    private static final NetworkRegistry INSTANCE = new NetworkRegistry();
    private final Map<String, IPacketHandler> channelHandlers = new HashMap<>();

    private NetworkRegistry() {}

    public static NetworkRegistry instance() {
        return INSTANCE;
    }

    /** Register {@code handler} as the receiver for packets on {@code channel}. */
    public void registerChannel(IPacketHandler handler, String channel) {
        if (handler == null || channel == null || channel.isEmpty()) return;
        IPacketHandler previous = channelHandlers.put(channel, handler);
        if (previous != null && previous != handler) {
            FishModLoader.LOGGER.warn(
                    "NetworkRegistry: replacing handler for channel '{}' (was {}, now {})",
                    channel, previous.getClass().getName(), handler.getClass().getName());
        }
    }

    /** @return the handler registered for {@code channel}, or null. */
    public IPacketHandler getHandler(String channel) {
        return channel == null ? null : channelHandlers.get(channel);
    }

    public boolean isChannelRegistered(String channel) {
        return channel != null && channelHandlers.containsKey(channel);
    }
}
