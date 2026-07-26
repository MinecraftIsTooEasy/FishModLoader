package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockBaseRailLogic;
import net.minecraft.block.BlockRailBase;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockBaseRailLogic.class)
public abstract class BlockBaseRailLogicMixin {
    @Shadow
    private boolean isStraightRail;

    @Shadow
    private int railX;

    @Shadow
    private int railY;

    @Shadow
    private int railZ;

    @Shadow
    private World logicWorld;

    @Shadow
    public abstract void setBasicRail(int meta);

    @Unique
    private boolean canMakeSlopes;

    /**
     * @reason Forge compat: simplify to just use metadata directly
     */
    /** NOTE: MITE has no updateRailMetadata on BlockBaseRailLogic, so this cannot be an @Overwrite
     *  (mixin apply would fail hard). Kept inert. See PLAN.md. */
    @Unique
    public void updateRailMetadata() {
        int l = this.logicWorld.getBlockId(this.railX, this.railY, this.railZ);
        BlockRailBase target = (BlockRailBase) BlockRailBase.blocksList[l];
        int meta = this.logicWorld.getBlockMetadata(this.railX, this.railY, this.railZ);
        if (target.isPowered()) {
            meta = meta & 7;
        }
        this.isStraightRail = !target.isPowered();
        this.canMakeSlopes = true;
        this.setBasicRail(meta);
    }
}
