package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.Map;

@Mixin(Chunk.class)
public abstract class ChunkMixin {

    @Shadow
    private World worldObj;

    @Shadow
    private ExtendedBlockStorage[] storageArrays;

    @Shadow
    @Final
    public int xPosition;

    @Shadow
    @Final
    public int zPosition;

    @Shadow
    public abstract int getBlockID(int par1, int par2, int par3);

    @Shadow
    public abstract int getBlockMetadata(int par1, int par2, int par3);

    @Shadow
    private Map chunkTileEntityMap;

    @Shadow
    public boolean isChunkLoaded;

    @Unique
    private static final int BLOCK_ID_MASK = 0xFF;

    @Inject(method = "fillChunk([BIIZ)V", at = @At("HEAD"))
    private void fmlForgeFillChunkPre(byte[] par1ArrayOfByte, int par2, int par3, boolean par4, CallbackInfo ci) {
        Iterator iterator = chunkTileEntityMap.values().iterator();
        while (iterator.hasNext()) {
            TileEntity tileEntity = (TileEntity) iterator.next();
            tileEntity.updateContainingBlockInfo();
            tileEntity.getBlockMetadata();
            tileEntity.getBlockType();
        }
    }

    @ModifyVariable(method = "getBlockLightOpacity", at = @At("RETURN"), ordinal = 0)
    private int fmlForgeGetBlockLightOpacity(int original, int par1, int par2, int par3) {
        int x = (xPosition << 4) + par1;
        int z = (zPosition << 4) + par3;
        Block block = Block.blocksList[getBlockID(par1, par2, par3)];
        return (block == null ? 0 : Block.lightOpacity[getBlockID(par1, par2, par3)]);
    }

    @Inject(method = "onChunkLoad", at = @At("TAIL"))
    private void fmlForgeOnChunkLoad(CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new ChunkEvent.Load((Chunk)(Object)this));
    }

    @Inject(method = "onChunkUnload", at = @At("TAIL"))
    private void fmlForgeOnChunkUnload(CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new ChunkEvent.Unload((Chunk)(Object)this));
    }
}
