package net.xiaoyu233.fml.reload.transform.forge_compat;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkDataEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AnvilChunkLoader.class)
public abstract class AnvilChunkLoaderMixin {
    
    @Inject(method = "checkedReadChunkFromNBT",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/storage/AnvilChunkLoader;readChunkFromNBT(Lnet/minecraft/world/World;Lnet/minecraft/nbt/NBTTagCompound;)Lnet/minecraft/world/chunk/Chunk;",
                    shift = At.Shift.AFTER, ordinal = 1))
    private void fmlForgeOnChunkLoad(World par1World, int par2, int par3, NBTTagCompound par4NBTTagCompound, CallbackInfoReturnable<Chunk> cir, @Local(name = "var5") Chunk chunk) {
        MinecraftForge.EVENT_BUS.post(new ChunkDataEvent.Load(chunk, par4NBTTagCompound));
    }
    
    @Inject(method = "saveChunk",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/storage/AnvilChunkLoader;writeChunkToNBT(Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/World;Lnet/minecraft/nbt/NBTTagCompound;)V",
                    ordinal = 0))
    private void fmlForgeOnChunkSave(World par1World, Chunk par2Chunk, CallbackInfo ci, @Local(name = "var3") NBTTagCompound nbt) {
        MinecraftForge.EVENT_BUS.post(new ChunkDataEvent.Save(par2Chunk, nbt));
    }
}
