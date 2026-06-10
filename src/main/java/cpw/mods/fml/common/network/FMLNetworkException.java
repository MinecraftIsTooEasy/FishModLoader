package cpw.mods.fml.common.network;

/**
 * Stub for cpw.mods.fml.common.network.FMLNetworkException (Forge 1.6.4).
 *
 * Upstream this extends IOException (checked). We make it a RuntimeException
 * here so callers don't have to redeclare throws — semantically still
 * catchable as the upstream type via try/catch (...) statements that
 * matched on this class. Stage 6 may re-checked-exception this if any mod
 * relies on the IOException relationship.
 */
public class FMLNetworkException extends RuntimeException {
    public FMLNetworkException(String message) { super(message); }
    public FMLNetworkException(String message, Throwable cause) { super(message, cause); }
    public FMLNetworkException(Throwable cause) { super(cause); }
}

