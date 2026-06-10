package cpw.mods.fml.common.event;

import cpw.mods.fml.common.ModContainer;

import java.io.File;

/**
 * Base class for FML lifecycle events. Mirrors Forge 1.6.4 API surface.
 */
public class FMLStateEvent {
    protected final Object[] data;

    public FMLStateEvent(Object... eventData) {
        this.data = eventData;
    }

    public ModContainer getModContainer() {
        return null;
    }

    /** Suggested config file location (if any). */
    public File getSuggestedConfigurationFile() {
        return null;
    }

    public Object[] getModData() {
        return data;
    }
}
