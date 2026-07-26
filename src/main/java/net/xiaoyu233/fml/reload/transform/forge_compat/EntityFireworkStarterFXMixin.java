package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.particle.EntityFireworkStarterFX;
import net.minecraft.nbt.NBTTagList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link EntityFireworkStarterFX}.
 * <p>
 * Adds null-checks for the {@code fireworkExplosions} NBTTagList
 * before accessing it.
 */
@Mixin(EntityFireworkStarterFX.class)
public class EntityFireworkStarterFXMixin {

    @Shadow
    private NBTTagList fireworkExplosions;

    /**
     * Placeholder: The patch adds null-checks around
     * {@code this.fireworkExplosions} before calling
     * {@code tagCount()} and other operations.
     * <p>
     * These are body-level changes within the constructor that cannot
     * be cleanly injected.
     */
    @Unique
    private void fmlForgeFireworkNullCheck() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for fireworkExplosions null checks.");
    }
}
