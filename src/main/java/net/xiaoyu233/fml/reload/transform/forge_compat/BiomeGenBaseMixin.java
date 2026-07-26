package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.biome.BiomeDecorator;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.gen.feature.WorldGenerator;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.BiomeEvent;
import net.minecraftforge.event.terraingen.DeferredBiomeDecorator;
import net.xiaoyu233.fml.util.ReflectHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BiomeGenBase.class)
public abstract class BiomeGenBaseMixin {
    @Shadow public int waterColorMultiplier;
    @Shadow public BiomeDecorator theBiomeDecorator;
    @Shadow public WorldGenerator worldGeneratorBigTree;
    @Shadow public abstract int getBiomeGrassColor();
    @Shadow public abstract int getBiomeFoliageColor();

    @Unique
    public BiomeDecorator getModdedBiomeDecorator(BiomeDecorator original) {
        return new DeferredBiomeDecorator(ReflectHelper.dyCast(this), original);
    }

    @Unique
    public int getWaterColorMultiplier() {
        BiomeEvent.GetWaterColor event = new BiomeEvent.GetWaterColor(ReflectHelper.dyCast(this), this.waterColorMultiplier);
        MinecraftForge.EVENT_BUS.post(event);
        return event.newColor;
    }

    @Unique
    public int getModdedBiomeGrassColor(int original) {
        BiomeEvent.GetGrassColor event = new BiomeEvent.GetGrassColor(ReflectHelper.dyCast(this), original);
        MinecraftForge.EVENT_BUS.post(event);
        return event.newColor;
    }

    @Unique
    public int getModdedBiomeFoliageColor(int original) {
        BiomeEvent.GetFoliageColor event = new BiomeEvent.GetFoliageColor(ReflectHelper.dyCast(this), original);
        MinecraftForge.EVENT_BUS.post(event);
        return event.newColor;
    }

    @Inject(method = "createBiomeDecorator", at = @At("RETURN"), cancellable = true)
    private void fmlForgeOnCreateBiomeDecorator(CallbackInfoReturnable<BiomeDecorator> cir) {
        cir.setReturnValue(getModdedBiomeDecorator(cir.getReturnValue()));
    }

    @Inject(method = "getBiomeGrassColor", at = @At("RETURN"), cancellable = true)
    private void fmlForgeOnGetBiomeGrassColor(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(getModdedBiomeGrassColor(cir.getReturnValue()));
    }

    @Inject(method = "getBiomeFoliageColor", at = @At("RETURN"), cancellable = true)
    private void fmlForgeOnGetBiomeFoliageColor(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(getModdedBiomeFoliageColor(cir.getReturnValue()));
    }
}
