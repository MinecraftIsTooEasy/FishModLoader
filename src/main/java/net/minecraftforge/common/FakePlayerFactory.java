package net.minecraftforge.common;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Forge 1.6.4 FakePlayerFactory — pools {@link FakePlayer} instances per
 * (world, username) so mods that need to attribute world changes to a
 * synthetic player share the same handle.
 */
public class FakePlayerFactory {

    private static final Map<World, Map<String, FakePlayer>> WORLD_POOL = new WeakHashMap<>();

    /** Get or create a FakePlayer for {@code username} in {@code world}. */
    public static FakePlayer get(World world, String username) {
        if (world == null || username == null) return null;
        Map<String, FakePlayer> pool = WORLD_POOL.computeIfAbsent(world, w -> new WeakHashMap<>());
        FakePlayer player = pool.get(username);
        if (player == null) {
            player = new FakePlayer(world, username);
            pool.put(username, player);
        }
        return player;
    }

    /** Convenience: a fake player named "[Minecraft]". */
    public static FakePlayer getMinecraft(World world) {
        return get(world, "[Minecraft]");
    }

    /** Returns the player as a FakePlayer if it actually is one, else null. */
    public static FakePlayer unwrap(EntityPlayer player) {
        return player instanceof FakePlayer ? (FakePlayer) player : null;
    }

    /** Drop pooled fake players for an unloaded world. */
    public static void unloadWorld(World world) {
        WORLD_POOL.remove(world);
    }
}
