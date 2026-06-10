package cpw.mods.fml.client;

import net.minecraft.client.Minecraft;

/**
 * Stub for cpw.mods.fml.client.FMLClientHandler (Forge 1.6.4).
 * Singleton client-side accessor. Most methods are no-ops; the few that
 * matter route through the underlying Minecraft instance.
 */
public class FMLClientHandler {
    private static final FMLClientHandler INSTANCE = new FMLClientHandler();

    public static FMLClientHandler instance() {
        return INSTANCE;
    }

    public Minecraft getClient() {
        return Minecraft.getMinecraft();
    }

    public boolean hasOptifine() {
        return false;
    }

    public void showInGameModOptions(net.minecraft.client.gui.GuiIngameMenu menu) {}
    public void showGuiScreen(net.minecraft.client.gui.GuiScreen gui) {
        Minecraft.getMinecraft().displayGuiScreen(gui);
    }
}
