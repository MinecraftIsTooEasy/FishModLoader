package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge compatibility for {@link WorldClient}.
 * <p>
 * Modifications:
 * <ul>
 *   <li>Fire {@link WorldEvent.Load} after constructor setup.</li>
 *   <li>Override {@code updateWeather()} to call super then
 *       {@code updateWeatherBody()}.</li>
 *   <li>Set {@code mapStorage}, {@code isRemote}, call
 *       {@code finishSetup()} before {@code setSpawnLocation}.</li>
 * </ul>
 */
@Mixin(WorldClient.class)
public class WorldClientMixin {

    /**
     * Fires {@link WorldEvent.Load} after the WorldClient constructor
     * completes.
     * <p>
     * The Forge patch inserts the event call after
     * {@code setSpawnLocation(8, 64, 8)} at the end of the constructor.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void fmlForgeOnWorldClientInit(CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new WorldEvent.Load(((WorldClient)(Object)this)));
    }

    /**
     * Placeholder: The Forge patch makes the original
     * {@code updateWeather()} method call {@code super.updateWeather()}
     * and then moves the original body into a new method called
     * {@code updateWeatherBody()}. This is a structural rename/refactor
     * that requires direct bytecode patching.
     */
    @org.spongepowered.asm.mixin.Unique
    private void fmlForgeUpdateWeather() {
        throw new UnsupportedOperationException(
                "Method body refactoring required. See patches for updateWeather/updateWeatherBody.");
    }
}
