package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.World;
import net.minecraft.world.gen.ChunkProviderEnd;
import net.minecraft.world.gen.NoiseGeneratorOctaves;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.Event;
import net.minecraftforge.event.terraingen.ChunkProviderEvent;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.event.terraingen.TerrainGen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

@Mixin(ChunkProviderEnd.class)
public abstract class ChunkProviderEndMixin {

    @Shadow
    private Random endRNG;

    @Shadow
    private NoiseGeneratorOctaves noiseGen1;
    @Shadow
    private NoiseGeneratorOctaves noiseGen2;
    @Shadow
    private NoiseGeneratorOctaves noiseGen3;
    @Shadow
    private NoiseGeneratorOctaves noiseGen4;
    @Shadow
    private NoiseGeneratorOctaves noiseGen5;

    @Shadow
    private World endWorld;

    @Inject(method = "<init>(Lnet/minecraft/world/World;J)V", at = @At("RETURN"))
    private void fmlForgeInitNoise(World par1World, long par2, CallbackInfo ci) {
        NoiseGeneratorOctaves[] noiseGens = {noiseGen1, noiseGen2, noiseGen3, noiseGen4, noiseGen5};
        noiseGens = TerrainGen.getModdedNoiseGenerators(par1World, this.endRNG, noiseGens);
        this.noiseGen1 = noiseGens[0];
        this.noiseGen2 = noiseGens[1];
        this.noiseGen3 = noiseGens[2];
        this.noiseGen4 = noiseGens[3];
        this.noiseGen5 = noiseGens[4];
    }

    @Inject(method = "replaceBlocksForBiome", at = @At("HEAD"), cancellable = true)
    private void fmlForgeReplaceBiomeBlocks(int par1, int par2, byte[] par3ArrayOfByte, net.minecraft.world.biome.BiomeGenBase[] par4ArrayOfBiomeGenBase, CallbackInfo ci) {
        ChunkProviderEvent.ReplaceBiomeBlocks event = new ChunkProviderEvent.ReplaceBiomeBlocks(
                (ChunkProviderEnd)(Object)this, par1, par2, par3ArrayOfByte, par4ArrayOfBiomeGenBase);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.getResult() == Event.Result.DENY) ci.cancel();
    }

    @Inject(method = "initializeNoiseField", at = @At("HEAD"), cancellable = true)
    private void fmlForgeInitNoiseField(double[] par1ArrayOfDouble, int par2, int par3, int par4, int par5, int par6, int par7,
                                         CallbackInfoReturnable<double[]> cir) {
        ChunkProviderEvent.InitNoiseField event = new ChunkProviderEvent.InitNoiseField(
                (ChunkProviderEnd)(Object)this, par1ArrayOfDouble, par2, par3, par4, par5, par6, par7);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.getResult() == Event.Result.DENY) {
            cir.setReturnValue(event.noisefield);
        }
    }

    @Inject(method = "populate", at = @At("HEAD"))
    private void fmlForgePopulatePre(net.minecraft.world.chunk.IChunkProvider par1IChunkProvider, int par2, int par3, CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new PopulateChunkEvent.Pre(par1IChunkProvider, endWorld, endWorld.rand, par2, par3, false));
    }

    @Inject(method = "populate", at = @At("TAIL"))
    private void fmlForgePopulatePost(net.minecraft.world.chunk.IChunkProvider par1IChunkProvider, int par2, int par3, CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new PopulateChunkEvent.Post(par1IChunkProvider, endWorld, endWorld.rand, par2, par3, false));
    }
}
