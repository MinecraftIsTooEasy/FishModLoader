package net.xiaoyu233.fml.reload.transform.forge_compat.event;

import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderEnd;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

@Mixin(ChunkProviderEnd.class)
public abstract class ChunkProviderEndEventsMixin {
    @Shadow private World endWorld;

    @Inject(method = "populate(Lnet/minecraft/world/chunk/IChunkProvider;II)V", at = @At("HEAD"))
    private void fmlForgeOnPopulatePre(IChunkProvider chunkProvider, int chunkX, int chunkZ, CallbackInfo callbackInfo) {
        Random rand = new Random(endWorld.getSeed());
        long xSeed = (rand.nextLong() / 2L) * 2L + 1L;
        long zSeed = (rand.nextLong() / 2L) * 2L + 1L;
        rand.setSeed((long) chunkX * xSeed + (long) chunkZ * zSeed ^ endWorld.getSeed());
        MinecraftForge.EVENT_BUS.post(new PopulateChunkEvent.Pre(chunkProvider, endWorld, rand, chunkX, chunkZ, false));
    }

    @Inject(method = "populate(Lnet/minecraft/world/chunk/IChunkProvider;II)V",
            at = @At("RETURN"))
    private void fmlForgeOnPopulatePost(IChunkProvider chunkProvider, int chunkX, int chunkZ, CallbackInfo callbackInfo) {
        Random rand = new Random();
        MinecraftForge.EVENT_BUS.post(new PopulateChunkEvent.Post(chunkProvider, endWorld, rand, chunkX, chunkZ, false));
    }
}
