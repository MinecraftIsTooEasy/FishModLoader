package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockPane;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.util.Icon;
import net.minecraftforge.common.ForgeDirection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility mixin for RenderBlocks.
 * <p>
 * The Forge patch modifies several rendering methods deeply within their bodies:
 * <ul>
 *   <li>{@code renderBlockBed} — replaces metadata-based bed direction/head checks with
 *       {@link Block#getBedDirection} / {@link Block#isBedFoot}, and adds
 *       {@link #hasOverrideBlockTexture()} checks for breaking texture on underside faces.
 *       These changes are interleaved with tessellator operations and cannot be cleanly
 *       injected via {@code @Inject} at the method boundaries.</li>
 *   <li>{@code renderBlockFire} — replaces zero-arg {@code canBlockCatchFire} calls with
 *       {@link ForgeDirection}-aware variants ({@code UP, EAST, WEST, SOUTH, NORTH, DOWN}).
 *       This is a signature change inside conditional blocks.</li>
 *   <li>{@code renderBlockPane} — replaces {@link BlockPane#canThisPaneConnectToThisBlockID}
 *       with {@link BlockPane#canPaneConnectTo} + {@link ForgeDirection}. Again,
 *       a signature change inside the method body.</li>
 * </ul>
 * These body-level edits would require {@code @Overwrite} (which conflicts with other
 * mods) or direct bytecode patching of the target class. Placeholder methods that the
 * patched bodies call are provided below.
 */
@Mixin(RenderBlocks.class)
public class RenderBlocksMixin {

    @Shadow
    public Icon overrideBlockTexture;

    /**
     * Returns true when a breaking-texture override is active (i.e.
     * {@code overrideBlockTexture} is non-null). Called from the patched
     * {@code renderBlockBed} to ensure the breaking texture is applied
     * to the underside of beds.
     *
     * @return true if {@link #overrideBlockTexture} is not null
     */
    @Unique
    public boolean hasOverrideBlockTexture() {
        return this.overrideBlockTexture != null;
    }

    /**
     * Placeholder for the patched {@code renderBlockBed} method.
     * <p>
     * The patch replaces:
     * <pre>{@code
     * int l = this.blockAccess.getBlockMetadata(par2, par3, par4);
     * int i1 = BlockBed.getDirection(l);
     * boolean flag = BlockBed.isBlockHeadOfBed(l);
     * }</pre>
     * with:
     * <pre>{@code
     * int i1 = par1Block.getBedDirection(blockAccess, par2, par3, par4);
     * boolean flag = par1Block.isBedFoot(blockAccess, par2, par3, par4);
     * }</pre>
     * And adds {@code if (hasOverrideBlockTexture()) icon = overrideBlockTexture;}
     * after each {@code getBlockIcon} call.
     * <p>
     * These changes are scattered through the middle of the method and cannot be
     * applied via {@code @Inject} without an {@code @Overwrite}.
     */
    @Unique
    private void fmlForgeRenderBlockBed() {
        throw new UnsupportedOperationException(
                "Body-level patching required. The RenderBlocks class must be patched directly " +
                        "for bed rendering, fire spread visuals, and pane connection changes. " +
                        "See patches/minecraft/net/minecraft/client/renderer/RenderBlocks.java.patch");
    }

    /**
     * Placeholder for the patched fire-rendering code that uses
     * {@link ForgeDirection}-aware {@code canBlockCatchFire}.
     * <p>
     * The patch changes calls like:
     * <pre>{@code Block.fire.canBlockCatchFire(this.blockAccess, x, y, z)}</pre>
     * to:
     * <pre>{@code Block.fire.canBlockCatchFire(this.blockAccess, x, y, z, ForgeDirection.XXX)}</pre>
     * where {@code XXX} depends on which face is being rendered.
     */
    @Unique
    private void fmlForgeRenderBlockFire() {
        throw new UnsupportedOperationException(
                "Body-level patching required. See patches/minecraft/net/minecraft/client/renderer/RenderBlocks.java.patch");
    }

    /**
     * Placeholder for the patched pane-rendering code that uses
     * {@link BlockPane#canPaneConnectTo} with a {@link ForgeDirection} argument
     * instead of the old {@code canThisPaneConnectToThisBlockID}.
     */
    @Unique
    private void fmlForgeRenderBlockPane() {
        throw new UnsupportedOperationException(
                "Body-level patching required. See patches/minecraft/net/minecraft/client/renderer/RenderBlocks.java.patch");
    }
}
