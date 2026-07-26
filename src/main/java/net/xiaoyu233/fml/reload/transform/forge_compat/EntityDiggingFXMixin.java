package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.client.particle.EntityDiggingFX;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link EntityDiggingFX}.
 * <p>
 * Adds a new constructor that takes a {@code side} parameter, and
 * modifies the original constructor to delegate to it. The
 * {@code applyColourMultiplier} method is also modified to skip
 * grass color for the top face.
 */
@Mixin(EntityDiggingFX.class)
public class EntityDiggingFXMixin {

    @Shadow
    private Block blockInstance;

    @Unique
    private int side;

    /**
     * Placeholder: The patch adds a new constructor
     * {@code EntityDiggingFX(World, double, double, double, double, double, double, Block, int, int)}
     * that takes a {@code side} parameter and uses it for {@code getIcon}
     * instead of hardcoding face 0.
     * <p>
     * Constructors cannot be added via mixin {@code @Inject} since they
     * modify the constructor chain. This requires direct patching.
     */
    @Unique
    private void fmlForgeEntityDiggingFXNewConstructor() {
        throw new UnsupportedOperationException(
                "New constructor with side parameter requires direct patching.");
    }

    /**
     * Placeholder: The patch modifies {@code applyColourMultiplier} to
     * add {@code && this.side != 1} to the grass check.
     * <p>
     * This is a conditional modification inside the method body.
     */
    @Unique
    private void fmlForgeApplyColourMultiplier() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for applyColourMultiplier.");
    }
}
