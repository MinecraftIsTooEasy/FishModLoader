package net.xiaoyu233.fml.forge.event;

import cpw.mods.fml.common.TickRegistry;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumSet;

/**
 * Drives {@link TickRegistry} dispatch on the client side.
 *
 * <p>Forge 1.6.4 fired a logical tick group on every {@code Minecraft.runTick}
 * call: CLIENT first, then WORLD + PLAYER if the player is in a world. We
 * mirror that ordering — start hooks fire at HEAD, end hooks fire at RETURN,
 * so handlers see a consistent before/after pair around vanilla logic.
 */
@Mixin(Minecraft.class)
public abstract class TickHandlerClientMixin {

    @Inject(method = "runTick()V", at = @At("HEAD"))
    private void fmlForgeTickStart(CallbackInfo callbackInfo) {
        Minecraft self = (Minecraft) (Object) this;
        EnumSet<TickType> types = EnumSet.of(TickType.CLIENT);
        World world = self.theWorld;
        EntityPlayer player = self.thePlayer;
        if (world != null) types.add(TickType.WORLD);
        if (player != null) types.add(TickType.PLAYER);
        TickRegistry.dispatchStart(Side.CLIENT, types);
    }

    @Inject(method = "runTick()V", at = @At("RETURN"))
    private void fmlForgeTickEnd(CallbackInfo callbackInfo) {
        Minecraft self = (Minecraft) (Object) this;
        EnumSet<TickType> types = EnumSet.of(TickType.CLIENT);
        World world = self.theWorld;
        EntityPlayer player = self.thePlayer;
        if (world != null) types.add(TickType.WORLD);
        if (player != null) types.add(TickType.PLAYER);
        TickRegistry.dispatchEnd(Side.CLIENT, types);
    }
}
