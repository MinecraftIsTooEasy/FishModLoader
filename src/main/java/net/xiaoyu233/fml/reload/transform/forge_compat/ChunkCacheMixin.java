package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.util.EnumFace;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkCache.class)
public abstract class ChunkCacheMixin {

    @Shadow
    private Chunk[][] chunkArray;

    @Shadow
    private World worldObj;

    @Shadow
    public abstract int getBlockId(int par1, int par2, int par3);

    @Inject(method = "getBlockTileEntity(IIII)Lnet/minecraft/tileentity/TileEntity;",
            at = @At("HEAD"), cancellable = true)
    private void fmlForgeGetBlockTileEntity(int par1, int par2, int par3, int par4, CallbackInfoReturnable<TileEntity> cir) {
        int l = (par1 >> 4) - 0; // chunkX
        int i1 = (par3 >> 4) - 0; // chunkZ
        // Actually need the real chunkX/chunkZ - use local vars from the shadow
        if (l >= 0 && l < this.chunkArray.length && i1 >= 0 && i1 < this.chunkArray[l].length) {
            Chunk chunk = this.chunkArray[l][i1];
            cir.setReturnValue(chunk == null ? null : chunk.getChunkBlockTileEntity(par1 & 15, par2, par3 & 15));
        } else {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "getBlockMetadata(IIII)I",
            at = @At("HEAD"), cancellable = true)
    private void fmlForgeGetBlockMetadata(int par1, int par2, int par3, int par4, CallbackInfoReturnable<Integer> cir) {
        int l = (par1 >> 4) - 0;
        int i1 = (par3 >> 4) - 0;
        if (l >= 0 && l < this.chunkArray.length && i1 >= 0 && i1 < this.chunkArray[l].length) {
            Chunk chunk = this.chunkArray[l][i1];
            cir.setReturnValue(chunk == null ? 0 : chunk.getBlockMetadata(par1 & 15, par2, par3 & 15));
        } else {
            cir.setReturnValue(0);
        }
    }

    @Unique
    public boolean doesBlockHaveSolidTopSurface(int par1, int par2, int par3) {
        int id = getBlockId(par1, par2, par3);
        Block block = Block.blocksList[id];
        if (block == null) return false;
        int meta = this.worldObj.getBlockMetadata(par1, par2, par3);
        return block.isFaceFlatAndSolid(meta, EnumFace.TOP);
    }

    @Inject(method = "isAirBlock", at = @At("HEAD"), cancellable = true)
    private void fmlForgeIsAirBlock(int par1, int par2, int par3, CallbackInfoReturnable<Boolean> cir) {
        int id = getBlockId(par1, par2, par3);
        cir.setReturnValue(id == 0);
    }

    @Unique
    public boolean isBlockSolidOnSide(int x, int y, int z, EnumFace face, boolean _default) {
        int id = getBlockId(x, y, z);
        Block block = Block.blocksList[id];
        if (block == null) return _default;
        int meta = this.worldObj.getBlockMetadata(x, y, z);
        return block.isFaceFlatAndSolid(meta, face);
    }
}
