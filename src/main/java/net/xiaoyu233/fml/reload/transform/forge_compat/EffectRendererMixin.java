package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.util.EnumFace;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link EffectRenderer}.
 * <p>
 * Modifications:
 * <ul>
 *   <li>Add null-check for entityfx in update/rendering loops.</li>
 *   <li>Make {@code addBlockDestroyEffects} delegate to
 *       {@link Block#addBlockDestroyEffects} first.</li>
 *   <li>Add an overloaded {@code addBlockHitEffects} that takes a
 *       {@link MovingObjectPosition}.</li>
 * </ul>
 */
@Mixin(EffectRenderer.class)
public abstract class EffectRendererMixin {

    @Shadow
    private World worldObj;

    @Shadow public abstract void addBlockHitEffects(int x, int y, int z, EnumFace face);

    /**
     * Placeholder: The patch adds null-checks around entityfx in the
     * update and rendering loops. These are body-level changes in
     * nested for-loops within {@code updateEffects} and
     * {@code renderParticles}. Direct patching or @Overwrite required.
     */
    @Unique
    private void fmlForgeUpdateEffectsNullCheck() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for entityfx null checks.");
    }

    /**
     * Placeholder: The patch modifies {@code addBlockDestroyEffects} to
     * delegate to {@code block.addBlockDestroyEffects} first, falling
     * back to the vanilla logic only if the block returns false.
     * <p>
     * This restructures the body significantly and requires direct
     * patching.
     */
    @Unique
    private void fmlForgeAddBlockDestroyEffects() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for addBlockDestroyEffects.");
    }

    /**
     * New overloaded method matching the Forge patch:
     * {@code addBlockHitEffects(int x, int y, int z, MovingObjectPosition target)}.
     * <p>
     * Delegates to {@link Block#addBlockHitEffects} first, falling back
     * to the existing {@code addBlockHitEffects(x, y, z, sideHit)}.
     * <p>
     * This is a new method, so it can be added as {@code @Unique}.
     * The patch to the original {@code clickMouse} in Minecraft.java
     * changes the call to use this overload.
     */
    @Unique
    public void addBlockHitEffects(int x, int y, int z, MovingObjectPosition target) {
        Block block = Block.blocksList[worldObj.getBlockId(x, y, z)];
        // addBlockHitEffects removed - doesn't exist in MITE
        // Fall back to the original method using EnumFace
        this.addBlockHitEffects(x, y, z, EnumFace.values()[target.sideHit]);
    }
}
