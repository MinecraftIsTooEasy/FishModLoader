package cpw.mods.fml.relauncher;

import java.util.Map;

/**
 * Stub for cpw.mods.fml.relauncher.IFMLLoadingPlugin (Forge 1.6.4).
 *
 * Implemented by core mods that need to register class transformers and
 * inject early. Currently a marker interface — stage 3 (mod discovery)
 * will iterate implementors at boot.
 */
public interface IFMLLoadingPlugin {
    String[] getLibraryRequestClass();

    /** @return class names of class transformers to register. */
    String[] getASMTransformerClass();

    String getModContainerClass();

    String getSetupClass();

    void injectData(Map<String, Object> data);

    String getAccessTransformerClass();
}
