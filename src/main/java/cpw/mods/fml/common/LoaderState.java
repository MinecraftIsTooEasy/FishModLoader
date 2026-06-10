package cpw.mods.fml.common;

/**
 * Stub for cpw.mods.fml.common.LoaderState (Forge 1.6.4).
 * Tracks the current phase of loading.
 */
public enum LoaderState {
    NOINIT,
    LOADING,
    CONSTRUCTING,
    PREINITIALIZATION,
    INITIALIZATION,
    POSTINITIALIZATION,
    AVAILABLE,
    SERVER_ABOUT_TO_START,
    SERVER_STARTING,
    SERVER_STARTED,
    SERVER_STOPPING,
    SERVER_STOPPED,
    ERRORED;

    public enum ModState {
        UNLOADED,
        LOADED,
        CONSTRUCTED,
        PREINITIALIZED,
        INITIALIZED,
        POSTINITIALIZED,
        AVAILABLE,
        DISABLED,
        ERRORED
    }
}
