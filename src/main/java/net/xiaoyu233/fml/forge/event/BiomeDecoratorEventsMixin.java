package net.xiaoyu233.fml.forge.event;

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

/**
 * Translates Forge 1.6.4 BiomeDecorator patches into Mixin {@code @Inject}
 * hooks on MITE's {@link BiomeDecorator}.
 *
 * <p>Covered:
 * <ul>
 *   <li>{@code decorate()} HEAD — fires {@link DecorateBiomeEvent.Pre}.</li>
 *   <li>{@code decorate()} RETURN — fires {@link DecorateBiomeEvent.Post}.</li>
 * </ul>
 *
 * <p>The {@code TerrainGen.decorate(... ,EventType)} per-feature gating in
 * the Forge patch is not reproduced here — translating those would require
 * fine-grained ModifyConstant / Redirect injections per ore type, which is
 * deferred until mods that depend on it actually surface.
 */
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
