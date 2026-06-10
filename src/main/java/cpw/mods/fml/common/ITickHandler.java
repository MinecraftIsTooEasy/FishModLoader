package cpw.mods.fml.common;

import java.util.EnumSet;

/**
 * Stub for cpw.mods.fml.common.ITickHandler (Forge 1.6.4).
 *
 * <p>Tick handlers were the Forge 1.6 mechanism for hooking into the
 * client/server tick loop. They were registered with
 * {@code TickRegistry.registerTickHandler(handler, side)} and received
 * {@code tickStart}/{@code tickEnd} callbacks for each requested tick type.
 *
 * <p>FishModLoader does not yet drive these callbacks — the interface
 * exists purely so legacy mods that reference it (e.g. WorldEdit's tick
 * counter) can be loaded without {@link NoClassDefFoundError}. Mods that
 * rely on tick handlers for core gameplay behaviour will not function
 * correctly until {@code TickRegistry} is wired up.
 */
public interface ITickHandler {
    /** Called at the start of the tick. */
    void tickStart(EnumSet<TickType> type, Object... tickData);

    /** Called at the end of the tick. */
    void tickEnd(EnumSet<TickType> type, Object... tickData);

    /** Which tick types this handler wants to receive. */
    EnumSet<TickType> ticks();

    /** Human-readable label, used in logs and error messages. */
    String getLabel();
}
