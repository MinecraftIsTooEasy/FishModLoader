package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.World;
import net.minecraft.world.gen.ChunkProviderHell;
import net.minecraft.world.gen.MapGenRavine;
import net.minecraft.world.gen.NoiseGeneratorOctaves;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.Event;
import net.minecraftforge.event.terraingen.ChunkProviderEvent;
import net.minecraftforge.event.terraingen.DecorateBiomeEvent;
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

@Mixin(ChunkProviderHell.class)
public abstract class ChunkProviderHellMixin {

    @Shadow
    private Random hellRNG;

    @Shadow
    private NoiseGeneratorOctaves netherNoiseGen1;
    @Shadow
    private NoiseGeneratorOctaves netherNoiseGen2;
    @Shadow
    private NoiseGeneratorOctaves netherNoiseGen3;
    @Shadow
    private NoiseGeneratorOctaves slowsandGravelNoiseGen;
    @Shadow
    private NoiseGeneratorOctaves netherrackExculsivityNoiseGen;
    @Shadow
    private NoiseGeneratorOctaves netherNoiseGen6;
    @Shadow
    private NoiseGeneratorOctaves netherNoiseGen7;

    @Shadow
    private World worldObj;

    @Shadow
    private net.minecraft.world.gen.structure.MapGenNetherBridge genNetherBridge;
    @Shadow
    private MapGenRavine netherCaveGenerator;

    @Inject(method = "<init>(Lnet/minecraft/world/World;J)V", at = @At("RETURN"))
    private void fmlForgeInit(World par1World, long par2, CallbackInfo ci) {
        NoiseGeneratorOctaves[] noiseGens = {netherNoiseGen1, netherNoiseGen2, netherNoiseGen3, slowsandGravelNoiseGen, netherrackExculsivityNoiseGen, netherNoiseGen6, netherNoiseGen7};
        noiseGens = TerrainGen.getModdedNoiseGenerators(par1World, this.hellRNG, noiseGens);
        this.netherNoiseGen1 = noiseGens[0];
        this.netherNoiseGen2 = noiseGens[1];
        this.netherNoiseGen3 = noiseGens[2];
        this.slowsandGravelNoiseGen = noiseGens[3];
        this.netherrackExculsivityNoiseGen = noiseGens[4];
        this.netherNoiseGen6 = noiseGens[5];
        this.netherNoiseGen7 = noiseGens[6];
    }

    @Unique
    private boolean fmlForgeMapGensInitialized = false;

    @Inject(method = "<init>(Lnet/minecraft/world/World;J)V", at = @At("TAIL"))
    private void fmlForgeInitMapGens(World par1World, long par2, CallbackInfo ci) {
        if (!fmlForgeMapGensInitialized) {
            fmlForgeMapGensInitialized = true;
        }
    }

    @Inject(method = "replaceBlocksForBiome", at = @At("HEAD"), cancellable = true)
    private void fmlForgeReplaceBlocks(int par1, int par2, byte[] par3ArrayOfByte, CallbackInfo ci) {
        ChunkProviderEvent.ReplaceBiomeBlocks event = new ChunkProviderEvent.ReplaceBiomeBlocks(
                (ChunkProviderHell)(Object)this, par1, par2, par3ArrayOfByte, null);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.getResult() == Event.Result.DENY) ci.cancel();
    }

    @Inject(method = "initializeNoiseField", at = @At("HEAD"), cancellable = true)
    private void fmlForgeInitNoiseField(double[] par1ArrayOfDouble, int par2, int par3, int par4, int par5, int par6, int par7,
                                         CallbackInfoReturnable<double[]> cir) {
        ChunkProviderEvent.InitNoiseField event = new ChunkProviderEvent.InitNoiseField(
                (ChunkProviderHell)(Object)this, par1ArrayOfDouble, par2, par3, par4, par5, par6, par7);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.getResult() == Event.Result.DENY) {
            cir.setReturnValue(event.noisefield);
        }
    }

    @Inject(method = "populate", at = @At("HEAD"))
    private void fmlForgePopulatePre(net.minecraft.world.chunk.IChunkProvider par1IChunkProvider, int par2, int par3, CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new PopulateChunkEvent.Pre(par1IChunkProvider, worldObj, hellRNG, par2, par3, false));
        MinecraftForge.EVENT_BUS.post(new DecorateBiomeEvent.Pre(worldObj, hellRNG, par2 * 16, par3 * 16));
    }

    @Inject(method = "populate", at = @At("TAIL"))
    private void fmlForgePopulatePost(net.minecraft.world.chunk.IChunkProvider par1IChunkProvider, int par2, int par3, CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new DecorateBiomeEvent.Post(worldObj, hellRNG, par2 * 16, par3 * 16));
        MinecraftForge.EVENT_BUS.post(new PopulateChunkEvent.Post(par1IChunkProvider, worldObj, hellRNG, par2, par3, false));
    }
}
