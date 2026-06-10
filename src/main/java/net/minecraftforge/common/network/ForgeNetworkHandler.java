package net.minecraftforge.common.network;

import cpw.mods.fml.common.network.NetworkMod;
import cpw.mods.fml.common.network.NetworkModHandler;
import net.minecraftforge.common.ForgeDummyContainer;

/**
 * STUB. Original delegated to NetworkModHandler.configureNetworkMod which
 * we don't implement on the FML shim. Only acceptVersion is overridden.
 */
public class ForgeNetworkHandler extends NetworkModHandler {
    public ForgeNetworkHandler(ForgeDummyContainer container) {
        super(container, container.getClass().getAnnotation(NetworkMod.class));
    }

    @Override
    public boolean acceptVersion(String version) {
        return true;
    }
}
