package net.xiaoyu233.fml.reload.transform.forge_compat.event;

import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeDecorator;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.DecorateBiomeEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

@Mixin(BiomeDecorator.class)
public abstract class BiomeDecoratorEventsMixin {

    @Shadow protected World currentWorld;
    @Shadow protected Random randomGenerator;
    @Shadow protected int chunk_X;
    @Shadow protected int chunk_Z;

    @Inject(method = "decorate()V", at = @At("HEAD"))
    private void fmlForgeOnDecoratePre(CallbackInfo callbackInfo) {
        MinecraftForge.EVENT_BUS.post(new DecorateBiomeEvent.Pre(currentWorld, randomGenerator, chunk_X, chunk_Z));
    }

    @Inject(method = "decorate()V", at = @At("RETURN"))
    private void fmlForgeOnDecoratePost(CallbackInfo callbackInfo) {
        MinecraftForge.EVENT_BUS.post(new DecorateBiomeEvent.Post(currentWorld, randomGenerator, chunk_X, chunk_Z));
    }
}
