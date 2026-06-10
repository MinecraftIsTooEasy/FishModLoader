package cpw.mods.fml.relauncher;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Stub for cpw.mods.fml.relauncher.FMLInjectionData (Forge 1.6.4).
 * Holds early-injected runtime data (mc version, jar dir, etc).
 */
public class FMLInjectionData {
    private static final Map<String, Object> data = new HashMap<>();

    static {
        data.put("mccversion", "1.6.4");
        data.put("mcpversion", "8.11");
        data.put("McpDir", new File(".").getAbsoluteFile());
    }

    public static Object[] data() {
        return new Object[] {
                data.get("mccversion"),
                data.get("mcpversion"),
                data.get("mcversion"),
                data.get("McpDir"),
                data.get("McpDir"),
                data.get("McpDir")
        };
    }

    public static String mccversion() { return (String) data.get("mccversion"); }
    public static String mcpversion() { return (String) data.get("mcpversion"); }
}
