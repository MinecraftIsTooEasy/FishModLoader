package net.xiaoyu233.fml.vulkan.mixin.test;

import net.minecraft.ChatAllowedCharacters;
import net.minecraft.EnumGameType;
import net.minecraft.GuiMainMenu;
import net.minecraft.Minecraft;
import net.minecraft.WorldSettings;
import net.minecraft.WorldType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

/**
 * Test harness: loads (or creates) a singleplayer world from the main menu.
 * Activated by {@code -Dfml.vulkan.autoWorld=<world name>}; {@code -Dfml.vulkan.autoWorldSeed=<long>} fixes the seed of a world
 * that is created (default: random, like the "Create New World" screen), {@code -Dfml.vulkan.autoWorldType=<index>} its world type
 * (default 2, 1 = flat). An existing world with that name is loaded unchanged.
 * <p>
 * Triggered from the first {@code updateScreen} tick of the main menu, not from {@code initGui}: the first {@code initGui} runs inside
 * {@code Minecraft.startGame()} before {@code Minecraft.loadingScreen} exists, and {@code launchIntegratedServer} needs it. A tick of
 * the screen is the same context as a button click (inside {@code Minecraft.runTick}).
 * <p>
 * The injector has {@code require = 1}: when a MITE revision renames {@code updateScreen}, Mixin logs a "Critical injection failure"
 * banner instead of silently skipping the hook ({@code autoWorld} would then do nothing). The mixin config is not required, so the
 * game itself still starts.
 */
@Mixin(GuiMainMenu.class)
public class MixinGuiMainMenu {
    @Unique
    private static boolean fmlVk$autoWorldDone;

    @Inject(method = "updateScreen", at = @At("HEAD"), require = 1)
    private void fmlVk$loadAutoWorld(CallbackInfo ci) {
        if (fmlVk$autoWorldDone) {
            return;
        }

        String name = System.getProperty("fml.vulkan.autoWorld");
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) {
            fmlVk$autoWorldDone = true; // harness not requested
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.loadingScreen == null || mc.theWorld != null) {
            return; // the game is not up yet: try again on the next tick
        }
        fmlVk$autoWorldDone = true;

        // Folder name like GuiCreateWorld.makeUseableName (but without the "-" suffix: an existing world is loaded, not duplicated)
        String folder = name;
        for (char illegal : ChatAllowedCharacters.allowedCharactersArray) {
            folder = folder.replace(illegal, '_');
        }
        folder = folder.replaceAll("[\\./\"]", "_");

        // GuiSelectWorld passes null settings for an existing world, GuiCreateWorld the new world's settings
        WorldSettings settings = null;
        if (mc.getSaveLoader().getWorldInfo(folder) == null) {
            long seed = new Random().nextLong();
            String seedProperty = System.getProperty("fml.vulkan.autoWorldSeed");
            if (seedProperty != null && !seedProperty.trim().isEmpty()) {
                try {
                    seed = Long.parseLong(seedProperty.trim());
                } catch (NumberFormatException e) {
                    System.err.println("[FML] Invalid fml.vulkan.autoWorldSeed '" + seedProperty + "', using a random seed");
                }
            }
            // Defaults of the Create New World screen: survival, structures on, not hardcore, world type index 2, skills off
            // (fml.vulkan.autoWorldType=<index> picks another type, e.g. 1 = flat for a controlled test scene)
            int typeIndex = 2;
            String typeProperty = System.getProperty("fml.vulkan.autoWorldType");
            if (typeProperty != null && !typeProperty.trim().isEmpty()) {
                try {
                    typeIndex = Integer.parseInt(typeProperty.trim());
                } catch (NumberFormatException e) {
                    System.err.println("[FML] Invalid fml.vulkan.autoWorldType '" + typeProperty + "', using " + typeIndex);
                }
            }
            WorldType type = typeIndex >= 0 && typeIndex < WorldType.worldTypes.length && WorldType.worldTypes[typeIndex] != null
                    ? WorldType.worldTypes[typeIndex] : WorldType.worldTypes[2];
            settings = new WorldSettings(seed, EnumGameType.SURVIVAL, true, false, type, false);
        }

        mc.displayGuiScreen(null);
        mc.launchIntegratedServer(folder, name, settings);
    }
}
