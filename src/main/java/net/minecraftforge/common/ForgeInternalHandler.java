package net.minecraftforge.common;

/**
 * STUB. Original ForgeInternalHandler is the @ForgeSubscribe handler
 * registered on EVENT_BUS that does internal entity/world bookkeeping
 * (persistent UUIDs, drops). On stock 1.6.4 MITE several of those vanilla
 * fields/methods don't exist; we leave it as a marker class so listeners
 * registered against it continue to type-check.
 */
public class ForgeInternalHandler {
    // intentionally empty; bus registration is no-op-safe
}
