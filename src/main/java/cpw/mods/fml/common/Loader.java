package cpw.mods.fml.common;

import net.xiaoyu233.fml.FishModLoader;

import java.io.File;
import java.util.List;

/**
 * Stub for cpw.mods.fml.common.Loader (Forge 1.6.4).
 * Singleton mod registry accessor. Returns empty/null for now;
 * the real wiring will be installed when LegacyModParser starts
 * registering Forge mods as ModContainers.
 */
public class Loader {
    private static final Loader INSTANCE = new Loader();

    private Loader() {}

    public static Loader instance() {
        return INSTANCE;
    }

    public ModContainer activeModContainer() {
        return null;
    }

    public List<ModContainer> getModList() {
        return net.xiaoyu233.fml.modfixer.LegacyModLifecycle.getContainers();
    }

    public List<ModContainer> getActiveModList() {
        return getModList();
    }

    public boolean isModLoaded(String modname) {
        if (FishModLoader.hasMod(modname)) return true;
        for (ModContainer c : getModList()) {
            if (modname.equals(c.getModId())) return true;
        }
        return false;
    }

    public ModContainer getIndexedModList() {
        return null;
    }

    public String getMCVersionString() {
        return "1.6.4";
    }

    public String getFMLVersionString() {
        return FishModLoader.VERSION;
    }

    public File getConfigDir() {
        return FishModLoader.CONFIG_DIR;
    }

    public boolean hasReachedState(LoaderState state) {
        return getLoaderState().ordinal() >= state.ordinal();
    }

    public LoaderState getLoaderState() {
        return net.xiaoyu233.fml.modfixer.LegacyModLifecycle.getState();
    }

    public List<ModContainer> getActiveModList(boolean includeFML) {
        return getModList();
    }
}
