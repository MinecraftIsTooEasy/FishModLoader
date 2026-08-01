package net.xiaoyu233.fml.modfixer;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBreakInfo;
import net.minecraft.entity.player.EntityPlayer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Bridges the legacy 1.6.4 Forge harvest callback into MITE's BlockBreakInfo drop path. */
public final class LegacyForgeBlockHarvestBridge {
    private static final String LEGACY_HARVEST = "func_71893_a";
    private static final Class<?>[] SIGNATURE = {
            net.minecraft.world.World.class, EntityPlayer.class,
            int.class, int.class, int.class, int.class
    };
    private static final ConcurrentHashMap<Class<?>, Optional<Method>> CALLBACKS = new ConcurrentHashMap<>();

    private LegacyForgeBlockHarvestBridge() {}

    /**
     * Runs a mod override once and tells the caller to suppress MITE's default drop.
     * Only player harvests on the logical server qualify.
     */
    public static boolean invokeLegacyHarvest(Block block, BlockBreakInfo info) {
        if (block == null || info == null || info.world == null || info.world.isRemote || !info.wasHarvestedByPlayer()) {
            return false;
        }
        EntityPlayer player = info.getResponsiblePlayer();
        if (player == null) return false;
        Optional<Method> callback = CALLBACKS.computeIfAbsent(block.getClass(), LegacyForgeBlockHarvestBridge::findCallback);
        if (!callback.isPresent()) return false;
        try {
            callback.get().invoke(block, info.world, player, info.x, info.y, info.z, info.getMetadata());
            return true;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access legacy Forge harvest callback on " + block.getClass().getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Legacy Forge harvest callback failed on " + block.getClass().getName(), cause);
        }
    }

    private static Optional<Method> findCallback(Class<?> type) {
        try {
            Method method = type.getMethod(LEGACY_HARVEST, SIGNATURE);
            // The compatibility mixin adds a generic method to Block.  Only a real
            // subclass override represents legacy mod behavior.
            return method.getDeclaringClass() == Block.class ? Optional.empty() : Optional.of(method);
        } catch (NoSuchMethodException ignored) {
            return Optional.empty();
        }
    }
}
