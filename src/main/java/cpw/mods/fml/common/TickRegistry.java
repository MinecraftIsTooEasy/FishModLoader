package cpw.mods.fml.common;

import cpw.mods.fml.relauncher.Side;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reimplementation of Forge 1.6.4 {@code cpw.mods.fml.common.TickRegistry}.
 *
 * <p>Mods register {@link ITickHandler} instances per side (CLIENT / SERVER).
 * FishModLoader's tick mixins call {@link #dispatchStart} / {@link #dispatchEnd}
 * once per game tick on each side, so handlers receive their tickStart/tickEnd
 * callbacks for the {@link TickType}s they declared.
 *
 * <p>Threading: the tick dispatch happens on the Minecraft client thread or
 * the integrated server thread. Registration may happen from any thread
 * (typically PreInit / Init), so the backing list is copy-on-write.
 *
 * <p>What's NOT implemented (intentionally minimal scope to support common
 * Forge 1.6.4 mods like WorldEdit):
 * <ul>
 *   <li>{@code RENDER} tick — no partial-tick fraction is passed.</li>
 *   <li>{@code GUI} tick — caller is expected to use the active screen
 *       directly via Minecraft.currentScreen.</li>
 *   <li>Fine-grained tick data per type (the per-type {@code tickData}
 *       payload is left empty).</li>
 * </ul>
 * Mods relying on those edge cases may need additional support; the basic
 * CLIENT/SERVER/WORLD/PLAYER ticks cover most use cases (counters, timers,
 * scheduled cleanup, etc.).
 */
public final class TickRegistry {
    private static final List<ITickHandler> CLIENT_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<ITickHandler> SERVER_HANDLERS = new CopyOnWriteArrayList<>();

    private TickRegistry() {}

    /**
     * Forge 1.6.4 entry point. Mods call this in PreInit/Init.
     */
    public static void registerTickHandler(ITickHandler handler, Side side) {
        if (handler == null || side == null) return;
        if (side == Side.CLIENT) {
            CLIENT_HANDLERS.add(handler);
        } else {
            SERVER_HANDLERS.add(handler);
        }
    }

    /**
     * Remove a previously-registered handler. Forge 1.6 doesn't actually
     * expose this, but it's useful for hot-reloading dev workflows.
     */
    public static void unregisterTickHandler(ITickHandler handler, Side side) {
        if (handler == null || side == null) return;
        if (side == Side.CLIENT) {
            CLIENT_HANDLERS.remove(handler);
        } else {
            SERVER_HANDLERS.remove(handler);
        }
    }

    /**
     * Called by FishModLoader tick mixins at the start of every tick on
     * the given side. Walks all registered handlers and forwards
     * {@link ITickHandler#tickStart} for the tick types they care about.
     */
    public static void dispatchStart(Side side, EnumSet<TickType> firingTypes, Object... tickData) {
        List<ITickHandler> list = side == Side.CLIENT ? CLIENT_HANDLERS : SERVER_HANDLERS;
        if (list.isEmpty()) return;
        for (ITickHandler handler : list) {
            EnumSet<TickType> wanted = handler.ticks();
            if (wanted == null || wanted.isEmpty()) continue;
            EnumSet<TickType> intersection = EnumSet.copyOf(wanted);
            intersection.retainAll(firingTypes);
            if (intersection.isEmpty()) continue;
            try {
                handler.tickStart(intersection, tickData);
            } catch (Throwable thrown) {
                FMLLog.warning("Tick handler %s threw on tickStart: %s",
                        handler.getLabel(), thrown);
            }
        }
    }

    /** End-of-tick counterpart of {@link #dispatchStart}. */
    public static void dispatchEnd(Side side, EnumSet<TickType> firingTypes, Object... tickData) {
        List<ITickHandler> list = side == Side.CLIENT ? CLIENT_HANDLERS : SERVER_HANDLERS;
        if (list.isEmpty()) return;
        for (ITickHandler handler : list) {
            EnumSet<TickType> wanted = handler.ticks();
            if (wanted == null || wanted.isEmpty()) continue;
            EnumSet<TickType> intersection = EnumSet.copyOf(wanted);
            intersection.retainAll(firingTypes);
            if (intersection.isEmpty()) continue;
            try {
                handler.tickEnd(intersection, tickData);
            } catch (Throwable thrown) {
                FMLLog.warning("Tick handler %s threw on tickEnd: %s",
                        handler.getLabel(), thrown);
            }
        }
    }
}
