package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.gen.structure.MapGenStronghold;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Arrays;

@Mixin(MapGenStronghold.class)
public abstract class MapGenStrongholdMixin {

    @Unique
    private static ArrayList<BiomeGenBase> allowedBiomes = new ArrayList<BiomeGenBase>(Arrays.asList(
            BiomeGenBase.desert, BiomeGenBase.forest, BiomeGenBase.extremeHills,
            BiomeGenBase.swampland, BiomeGenBase.taiga, BiomeGenBase.icePlains,
            BiomeGenBase.iceMountains, BiomeGenBase.desertHills, BiomeGenBase.forestHills,
            BiomeGenBase.extremeHillsEdge, BiomeGenBase.jungle, BiomeGenBase.jungleHills
    ));

    @Shadow
    private BiomeGenBase[] allowedBiomeGenBases;

    @Inject(method = "<init>()V", at = @At("RETURN"))
    private void onConstructDefault(CallbackInfo ci) {
        this.allowedBiomeGenBases = allowedBiomes.toArray(new BiomeGenBase[0]);
    }

    @Inject(method = "<init>(Ljava/util/Map;)V", at = @At("RETURN"))
    private void onConstructWithMap(java.util.Map<?, ?> par1Map, CallbackInfo ci) {
        this.allowedBiomeGenBases = allowedBiomes.toArray(new BiomeGenBase[0]);
    }
}
