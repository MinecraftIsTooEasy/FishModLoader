package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraft.world.gen.structure.ComponentVillageStartPiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ComponentVillageStartPiece.class)
public abstract class ComponentVillageStartPieceMixin {

    @Unique
    public BiomeGenBase biome;

    @Inject(method = "<init>(Lnet/minecraft/world/biome/WorldChunkManager;IILnet/minecraft/world/gen/structure/ComponentVillageStartPiece;IILjava/util/List;I)V",
            at = @At("RETURN"))
    private void fmlForgeInitBiome(WorldChunkManager par1WorldChunkManager,
                                    int par2, int par3,
                                    ComponentVillageStartPiece par4ComponentVillageStartPiece,
                                    int par5, int par6, java.util.List par7List, int par8, CallbackInfo ci) {
    }
}
