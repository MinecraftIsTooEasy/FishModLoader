package cpw.mods.fml.common;

/**
 * Stub for cpw.mods.fml.common.TickType (Forge 1.6.4).
 *
 * <p>Names mirror the Forge 1.6.4 enum so legacy mods can reference each
 * constant by the original identifier. FishModLoader does not currently
 * fire tick events of any of these types — the enum exists so
 * {@link ITickHandler} implementations can be loaded without errors.
 */
public enum TickType {
    /** Client-side: every render frame, post-tick. */
    RENDER,
    /** Client-side: every game tick. */
    CLIENT,
    /** Server-side: every game tick. */
    SERVER,
    /** Both sides: WorldServer.tick / WorldClient.tick. */
    WORLD,
    /** Both sides: each player tick. */
    PLAYER,
    /** Client-side: GUI screen tick. */
    GUI,
    /** Both sides: incoming packet processing. */
    PACKET,
    /** Bookkeeping tick fired alongside WORLD. */
    WORLDLOAD
}
