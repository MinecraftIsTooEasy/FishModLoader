package cpw.mods.fml.common.network;

import cpw.mods.fml.common.ModContainer;

/**
 * Stub for cpw.mods.fml.common.network.NetworkModHandler (Forge 1.6.4).
 * Currently empty; connects a mod to its network configuration in upstream FML.
 */
public class NetworkModHandler {
    public NetworkModHandler(ModContainer container, NetworkMod mod) {}

    public boolean acceptVersion(String version) { return true; }
    public boolean isNetworkMod() { return false; }
    public ModContainer getContainer() { return null; }
}
