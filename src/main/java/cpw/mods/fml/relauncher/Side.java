package cpw.mods.fml.relauncher;

/**
 * Stub for cpw.mods.fml.relauncher.Side from Forge 1.6.4 FML.
 * Mirrors the original public API so Forge mods can compile/load.
 */
public enum Side {
    CLIENT,
    SERVER;

    public boolean isClient() {
        return this == CLIENT;
    }

    public boolean isServer() {
        return this == SERVER;
    }

    public Side opposite() {
        return this == CLIENT ? SERVER : CLIENT;
    }
}
