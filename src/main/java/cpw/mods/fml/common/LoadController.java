package cpw.mods.fml.common;

/**
 * Stub for cpw.mods.fml.common.LoadController (Forge 1.6.4).
 * Drives the lifecycle in upstream FML; here it's an empty placeholder.
 */
public class LoadController {
    public LoaderState getState() {
        return LoaderState.AVAILABLE;
    }
    public ModContainer activeContainer() {
        return null;
    }
    public void distributeStateMessage(LoaderState state, Object... eventData) {}
    public void distributeStateMessage(Class<?> customEvent) {}
}
