package cpw.mods.fml.common.event;

import net.minecraft.command.ICommand;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

public class FMLServerStartingEvent extends FMLStateEvent {
    private final List<ICommand> commands = new ArrayList<>();
    private final MinecraftServer server;

    public FMLServerStartingEvent(Object... eventData) {
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

    public void registerServerCommand(ICommand command) {
        commands.add(command);
    }

    public List<ICommand> getRegisteredCommands() {
        return commands;
    }
}
