package cpw.mods.fml.common.event;

import net.minecraft.server.MinecraftServer;

/**
 * Stub for cpw.mods.fml.common.event.FMLServerAboutToStartEvent (Forge 1.6.4 +).
 *
 * <p>Fired before the server has fully initialized — earlier than
 * {@link FMLServerStartingEvent}. Forge originally introduced this for
 * mods that need to install ServerCommandManager-style integrations
 * before vanilla command parsing kicks in.
 *
 * <p>FishModLoader does not currently fire this event. The class exists
 * so legacy mods that subscribe to it (e.g. WorldEdit's ForgeWorldEdit)
 * can be reflected without {@link NoClassDefFoundError}.
 */
public class FMLServerAboutToStartEvent extends FMLStateEvent {
    private final MinecraftServer server;

    public FMLServerAboutToStartEvent(Object... eventData) {
        super(eventData);
        MinecraftServer s = null;
        for (Object o : eventData) {
            if (o instanceof MinecraftServer) { s = (MinecraftServer) o; break; }
        }
        this.server = s;
    }

    public MinecraftServer getServer() {
        return server;
    }
}
