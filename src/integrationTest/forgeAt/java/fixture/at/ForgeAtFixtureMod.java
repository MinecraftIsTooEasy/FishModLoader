package fixture.at;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import net.xiaoyu233.fml.FishModLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Real Forge lifecycle fixture for the discover -> AT import -> class transform chain. */
@Mod(modid = "forge_at_fixture", name = "Forge AT Fixture", version = "1.0.0")
public final class ForgeAtFixtureMod {
    private static final String TARGET_CLASS = "fixture.at.LateLoadedTarget";

    @Mod.ServerStarted
    public void serverStarted(FMLServerStartedEvent event) throws Exception {
        // Keep the target out of this class's symbolic references so its first load
        // cannot happen before ForgeModDiscoverer imports the jar's AT rules.
        Class<?> target = Class.forName(TARGET_CLASS, true, getClass().getClassLoader());
        Field field = target.getDeclaredField("secret");
        int modifiers = field.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isFinal(modifiers)) {
            throw new AssertionError("AT flags not applied: " + Modifier.toString(modifiers));
        }
        Object instance = target.getDeclaredConstructor().newInstance();
        if (!"transformed".equals(field.get(instance))) {
            throw new AssertionError("AT field is not publicly readable after transformation");
        }
        FishModLoader.LOGGER.info("[Forge AT fixture] ASSERTION PASSED: LateLoadedTarget.secret is public and non-final");
    }
}
