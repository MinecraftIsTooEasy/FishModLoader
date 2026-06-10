package cpw.mods.fml.common.network;

/**
 * Stub for cpw.mods.fml.common.network.FMLPacket (Forge 1.6.4).
 * Used internally by upstream FML for mod-list / mod-id-map handshake.
 * Empty here; stage 6 will fill in when the network bridge ships.
 */
public abstract class FMLPacket {
    public abstract byte[] serialize();

    public abstract FMLPacket consumePacket(byte[] data);
    public enum Type { MOD_LIST_REQUEST, MOD_LIST_RESPONSE, MOD_IDENTIFIERS, MOD_MISSING }
}
