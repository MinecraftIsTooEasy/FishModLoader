package cpw.mods.fml.common.event;

import cpw.mods.fml.common.LoaderState;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Stub for cpw.mods.fml.common.event.FMLPreInitializationEvent.
 *
 * The pre-initialization event in Forge 1.6.4. Carries the suggested
 * configuration file location and a few utility accessors. Most fields
 * are placeholders — real wiring happens in the lifecycle dispatcher.
 */
public class FMLPreInitializationEvent extends FMLStateEvent {
    private final File configFile;
    private final File sourceFile;
    private final Properties versionProperties = new Properties();
    private final Map<String, String> modList = new HashMap<>();

    public FMLPreInitializationEvent(Object... eventData) {
        super(eventData);
        File suggested = null;
        File source = null;
        for (Object value : eventData) {
            if (value instanceof File) {
                if (suggested == null) {
                    suggested = (File) value;
                } else if (source == null) {
                    source = (File) value;
                }
            }
        }
        this.configFile = suggested;
        this.sourceFile = source;
    }

    @Override
    public File getSuggestedConfigurationFile() {
        return configFile;
    }

    public File getModConfigurationDirectory() {
        return new File("config");
    }

    public Properties getVersionProperties() {
        return versionProperties;
    }

    public LoaderState.ModState getModState() {
        return LoaderState.ModState.PREINITIALIZED;
    }

    /** Map of modId -> version, populated from the loaded mod list. */
    public Map<String, String> getModList() {
        return modList;
    }

    public File getSourceFile() {
        return sourceFile;
    }
}
