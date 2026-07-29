package net.xiaoyu233.fml.reload.transform.forge_compat;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.network.INetworkManager;
import net.minecraft.network.packet.Packet132TileEntityData;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(TileEntity.class)
public abstract class TileEntityMixin {

    @Shadow
    public World worldObj;

    @Shadow
    public int xCoord;

    @Shadow
    public int yCoord;

    @Shadow
    public int zCoord;

    @Shadow
    public abstract Block getBlockType();

    @Unique
    private boolean isVanilla = getClass().getName().startsWith("net.minecraft.tileentity");

    /**
     * Determines if this TileEntity requires update calls.
     */
    @Unique
    public boolean canUpdate() {
        return true;
    }

    /**
     * Called when you receive a TileEntityData packet.
     */
    @Unique
    public void onDataPacket(INetworkManager net, Packet132TileEntityData pkt) {
    }

    /**
     * Called when the chunk this TileEntity is on is Unloaded.
     */
    @Unique
    public void onChunkUnload() {
    }

    /**
     * Determines if this tile entity should be re-created when the ID, or Metadata changes.
     */
    @Unique
    public boolean shouldRefresh(int oldID, int newID, int oldMeta, int newMeta, World world, int x, int y, int z) {
        return !isVanilla || (oldID != newID);
    }

    @Unique
    public boolean shouldRenderInPass(int pass) {
        return pass == 0;
    }

    // The public static Forge constant cannot be added from a mixin class;
    // keep the shared render bound private and expose it through the method.
    @Unique
    private static final AxisAlignedBB INFINITE_EXTENT_AABB = AxisAlignedBB.getBoundingBox(
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);

    /**
     * Return an AxisAlignedBB that controls the visible scope of a TileEntitySpecialRenderer.
     */
    @SideOnly(Side.CLIENT)
    @Unique
    public AxisAlignedBB getRenderBoundingBox() {
        AxisAlignedBB bb = INFINITE_EXTENT_AABB;
        Block type = getBlockType();
        if (type == Block.enchantmentTable) {
            bb = AxisAlignedBB.getAABBPool().getAABB(xCoord, yCoord, zCoord, xCoord + 1, yCoord + 1, zCoord + 1);
        } else if (type == Block.chest || type == Block.chestTrapped) {
            bb = AxisAlignedBB.getAABBPool().getAABB(xCoord - 1, yCoord, zCoord - 1, xCoord + 2, yCoord + 2, zCoord + 2);
        } else if (type != null && type != Block.beacon) {
            // getCollisionBoundingBoxFromPool signature differs in MITE, use default bounding box
        }
        return bb;
    }
}
