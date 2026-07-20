package net.xiaoyu233.fml.reload.transform.forge_compat.event;

import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderHell;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

/**
 * Translates Forge 1.6.4 Nether populate-chunk events into Mixin
 * {@code @Inject} hooks on MITE's {@link ChunkProviderHell}.
 */
@Mixin(ChunkProviderHell.class)
public abstract class ChunkProviderHellEventsMixin {

    @Shadow private World worldObj;

    @Inject(method = "populate(Lnet/minecraft/world/chunk/IChunkProvider;II)V",
            at = @At("HEAD"))
    private void fmlForgeOnPopulatePre(IChunkProvider chunkProvider, int chunkX, int chunkZ,
                                       CallbackInfo callbackInfo) {
        Random rand = new Random(worldObj.getSeed());
        long xSeed = (rand.nextLong() / 2L) * 2L + 1L;
        long zSeed = (rand.nextLong() / 2L) * 2L + 1L;
        rand.setSeed((long) chunkX * xSeed + (long) chunkZ * zSeed ^ worldObj.getSeed());
        MinecraftForge.EVENT_BUS.post(new PopulateChunkEvent.Pre(
                chunkProvider, worldObj, rand, chunkX, chunkZ, false));
    }

    @Inject(method = "populate(Lnet/minecraft/world/chunk/IChunkProvider;II)V",
            at = @At("RETURN"))
    private void fmlForgeOnPopulatePost(IChunkProvider chunkProvider, int chunkX, int chunkZ,
                                        CallbackInfo callbackInfo) {
        Random rand = new Random();
        MinecraftForge.EVENT_BUS.post(new PopulateChunkEvent.Post(
                chunkProvider, worldObj, rand, chunkX, chunkZ, false));
    }
}
