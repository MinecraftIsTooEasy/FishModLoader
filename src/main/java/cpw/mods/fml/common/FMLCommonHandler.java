package cpw.mods.fml.common;

import cpw.mods.fml.relauncher.Side;
import net.minecraft.server.MinecraftServer;
import net.xiaoyu233.fml.FishModLoader;

/**
 * Stub for cpw.mods.fml.common.FMLCommonHandler (Forge 1.6.4).
 *
 * Singleton accessor used by Forge mods for cross-cutting concerns
 * (current side, server access, bus access). This shim exposes the
 * surface API and routes through FishModLoader where possible.
 */
public class FMLCommonHandler {
    private static final FMLCommonHandler INSTANCE = new FMLCommonHandler();
    private volatile MinecraftServer minecraftServerInstance;

    private FMLCommonHandler() {}

    public static FMLCommonHandler instance() {
        return INSTANCE;
    }

    public Side getSide() {
        return FishModLoader.isServer() ? Side.SERVER : Side.CLIENT;
    }

    public Side getEffectiveSide() {
        return getSide();
    }

    public MinecraftServer getMinecraftServerInstance() {
        MinecraftServer server = MinecraftServer.getServer();
        return server != null ? server : minecraftServerInstance;
    }

    public void setMinecraftServerInstance(MinecraftServer server) {
        this.minecraftServerInstance = server;
    }

    /** Forge 1.6 returned a Logger here; we route to FML's logger. */
    public org.apache.logging.log4j.Logger getFMLLogger() {
        return FishModLoader.LOGGER;
    }

    /** Stub for the common-bus accessor in newer FML; harmless on 1.6 mods that don't use it. */
    public Object bus() {
        return null;
    }

    /** Hook for ticks — no-op stub. Real wiring lives in the lifecycle dispatcher. */
    public void onPreServerTick() {}
    public void onPostServerTick() {}
    public void onPreClientTick() {}
    public void onPostClientTick() {}
    public void onWorldLoadTick(net.minecraft.world.World[] worlds) {}

    public void registerCrashCallable(java.util.concurrent.Callable<String> callable) {}

    public void raiseException(Throwable exception, String message, boolean stopGame) {
        if (stopGame) {
            throw new RuntimeException(message, exception);
        } else {
            FishModLoader.LOGGER.error(message, exception);
        }
    }

    public boolean isClient() {
        return getSide() == Side.CLIENT;
    }

    public boolean isServer() {
        return getSide() == Side.SERVER;
    }

    public ModContainer findContainerFor(Object mod) {
        for (ModContainer c : Loader.instance().getModList()) {
            if (c.matches(mod)) return c;
        }
        return null;
    }

    public ModContainer findContainerFor(String modId) {
        for (ModContainer c : Loader.instance().getModList()) {
            if (modId.equals(c.getModId())) return c;
        }
        return null;
    }
}
