package net.xiaoyu233.fml.reload.transform.forge_compat.event;

import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderGenerate;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.Event;
import net.minecraftforge.event.terraingen.ChunkProviderEvent;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.xiaoyu233.fml.util.ReflectHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

@Mixin(ChunkProviderGenerate.class)
public abstract class ChunkProviderGenerateEventsMixin {
    @Shadow private World worldObj;
    @Shadow private Random rand;

    @Inject(method = "replaceBlocksForBiome(II[B[Lnet/minecraft/world/biome/BiomeGenBase;)V", at = @At("HEAD"), cancellable = true)
    private void fmlForgeOnReplaceBiomeBlocks(int chunkX, int chunkZ, byte[] blockArray, BiomeGenBase[] biomeArray, CallbackInfo callbackInfo) {
        ChunkProviderEvent.ReplaceBiomeBlocks event = new ChunkProviderEvent.ReplaceBiomeBlocks(ReflectHelper.dyCast(this), chunkX, chunkZ, blockArray, biomeArray);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.getResult() == Event.Result.DENY) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "initializeNoiseField([DIIIIII)[D", at = @At("HEAD"), cancellable = true)
    private void fmlForgeOnInitializeNoiseField(double[] noisefield, int posX, int posY, int posZ, int sizeX, int sizeY, int sizeZ, CallbackInfoReturnable<double[]> callbackInfo) {
        ChunkProviderEvent.InitNoiseField event = new ChunkProviderEvent.InitNoiseField(ReflectHelper.dyCast(this), noisefield, posX, posY, posZ, sizeX, sizeY, sizeZ);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.getResult() == Event.Result.DENY) {
            callbackInfo.setReturnValue(event.noisefield);
        }
    }

    @Inject(method = "populate(Lnet/minecraft/world/chunk/IChunkProvider;II)V", at = @At("HEAD"))
    private void fmlForgeOnPopulatePre(IChunkProvider chunkProvider, int chunkX, int chunkZ, CallbackInfo callbackInfo) {
        MinecraftForge.EVENT_BUS.post(new PopulateChunkEvent.Pre(chunkProvider, worldObj, rand, chunkX, chunkZ, false));
    }

    @Inject(method = "populate(Lnet/minecraft/world/chunk/IChunkProvider;II)V", at = @At("RETURN"))
    private void fmlForgeOnPopulatePost(IChunkProvider chunkProvider, int chunkX, int chunkZ, CallbackInfo callbackInfo) {
        MinecraftForge.EVENT_BUS.post(new PopulateChunkEvent.Post(chunkProvider, worldObj, rand, chunkX, chunkZ, false));
    }
}
