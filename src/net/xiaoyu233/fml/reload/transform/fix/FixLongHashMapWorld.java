package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public abstract class FixLongHashMapWorld implements IBlockAccess {
    @Shadow protected IChunkProvider chunkProvider;
    @Unique private long key;

    @Unique
    private static int getHashedKey(long par0)
    {
        return (int)par0 + (int)(par0 >>> 32) * 92821;
    }

    @Inject(method = "getBlockId", at = @At(value = "HEAD"))
    public void getKey(int par1, int par2, int par3, CallbackInfoReturnable<Integer> cir) {
        this.key = (long)(par1 >> 4) & 0xFFFFFFFFL | ((long)(par3 >> 4) & 0xFFFFFFFFL) << 32;
    }

    @ModifyVariable(method = "getBlockId", at = @At(value = "STORE", ordinal = 0), name = "var4_1")
    public LongHashMapEntry injectedServer(LongHashMapEntry var4_1) {
        LongHashMap lhm = ((ChunkProviderServer) this.chunkProvider).loadedChunkHashMap;
        return lhm.hashArray[getHashedKey(this.key) & lhm.hashArray.length - 1];
    }

    @ModifyVariable(method = "getBlockId", at = @At(value = "STORE", ordinal = 2), name = "var4_1")
    public LongHashMapEntry injectedClient(LongHashMapEntry var4_1) {
        LongHashMap lhm = ((ChunkProviderClient) this.chunkProvider).chunkMapping;
        return lhm.hashArray[getHashedKey(this.key) & lhm.hashArray.length - 1];
    }
}
