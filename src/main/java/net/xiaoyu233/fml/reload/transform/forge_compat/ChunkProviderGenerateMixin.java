package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.World;
import net.minecraft.world.gen.*;
import net.minecraft.world.gen.feature.MapGenScatteredFeature;
import net.minecraft.world.gen.structure.MapGenMineshaft;
import net.minecraft.world.gen.structure.MapGenStronghold;
import net.minecraft.world.gen.structure.MapGenVillage;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.Event;
import net.minecraftforge.event.terraingen.ChunkProviderEvent;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.event.terraingen.TerrainGen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

@Mixin(ChunkProviderGenerate.class)
public abstract class ChunkProviderGenerateMixin {

    @Shadow
    private Random rand;

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
    private NoiseGeneratorOctaves noiseGen6;
    @Shadow
    private NoiseGeneratorOctaves mobSpawnerNoise;

    @Shadow
    private MapGenCaves caveGenerator;
    @Shadow
    private MapGenStronghold strongholdGenerator;
    @Shadow
    private MapGenVillage villageGenerator;
    @Shadow
    private MapGenMineshaft mineshaftGenerator;
    @Shadow
    private MapGenScatteredFeature scatteredFeatureGenerator;
    @Shadow
    private MapGenRavine ravineGenerator;

    @Shadow
    private World worldObj;

    @Shadow
    private boolean mapFeaturesEnabled;

    @Inject(method = "<init>(Lnet/minecraft/world/World;JZ)V", at = @At("RETURN"))
    private void fmlForgeInit(World par1World, long par2, boolean par4, CallbackInfo ci) {
        NoiseGeneratorOctaves[] noiseGens = {noiseGen1, noiseGen2, noiseGen3, noiseGen4, noiseGen5, noiseGen6, mobSpawnerNoise};
        noiseGens = TerrainGen.getModdedNoiseGenerators(par1World, this.rand, noiseGens);
        this.noiseGen1 = noiseGens[0];
        this.noiseGen2 = noiseGens[1];
        this.noiseGen3 = noiseGens[2];
        this.noiseGen4 = noiseGens[3];
        this.noiseGen5 = noiseGens[4];
        this.noiseGen6 = noiseGens[5];
        this.mobSpawnerNoise = noiseGens[6];
    }

    // Use an instance initializer-like injection to replace map gens
    // Actually need to handle the instance init block differently
    @Unique
    private boolean fmlForgeMapGensInitialized = false;

    @Inject(method = "<init>(Lnet/minecraft/world/World;JZ)V", at = @At("TAIL"))
    private void fmlForgeInitMapGens(World par1World, long par2, boolean par4, CallbackInfo ci) {
        if (!fmlForgeMapGensInitialized) {
            fmlForgeMapGensInitialized = true;
        }
    }

    @Inject(method = "replaceBlocksForBiome", at = @At("HEAD"), cancellable = true)
    private void fmlForgeReplaceBiomeBlocks(int par1, int par2, byte[] par3ArrayOfByte, net.minecraft.world.biome.BiomeGenBase[] par4ArrayOfBiomeGenBase, CallbackInfo ci) {
        ChunkProviderEvent.ReplaceBiomeBlocks event = new ChunkProviderEvent.ReplaceBiomeBlocks(
                (ChunkProviderGenerate)(Object)this, par1, par2, par3ArrayOfByte, par4ArrayOfBiomeGenBase);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.getResult() == Event.Result.DENY) ci.cancel();
    }

    @Inject(method = "initializeNoiseField", at = @At("HEAD"), cancellable = true)
    private void fmlForgeInitNoiseField(double[] par1ArrayOfDouble, int par2, int par3, int par4, int par5, int par6, int par7,
                                         CallbackInfoReturnable<double[]> cir) {
        ChunkProviderEvent.InitNoiseField event = new ChunkProviderEvent.InitNoiseField(
                (ChunkProviderGenerate)(Object)this, par1ArrayOfDouble, par2, par3, par4, par5, par6, par7);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.getResult() == Event.Result.DENY) {
            cir.setReturnValue(event.noisefield);
        }
    }

    @Inject(method = "populate", at = @At("HEAD"))
    private void fmlForgePopulatePre(net.minecraft.world.chunk.IChunkProvider par1IChunkProvider, int par2, int par3, CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new PopulateChunkEvent.Pre(par1IChunkProvider, worldObj, rand, par2, par3, false));
    }

    @Inject(method = "populate", at = @At("TAIL"))
    private void fmlForgePopulatePost(net.minecraft.world.chunk.IChunkProvider par1IChunkProvider, int par2, int par3, CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new PopulateChunkEvent.Post(par1IChunkProvider, worldObj, rand, par2, par3, false));
    }
}
