package cpw.mods.fml.common.registry;

import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.relauncher.Side;

/**
 * Forge 1.6.4 package-compat facade for tick-handler registration.
 */
public final class TickRegistry {
    private TickRegistry() {}

    public static void registerTickHandler(ITickHandler handler, Side side) {
        cpw.mods.fml.common.TickRegistry.registerTickHandler(handler, side);
    }
}
