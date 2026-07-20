package net.xiaoyu233.fml.reload.transform.forge_compat.event;

import cpw.mods.fml.common.TickRegistry;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumSet;

@Mixin(Minecraft.class)
public abstract class TickHandlerClientMixin {
    @Shadow public WorldClient theWorld;
    @Shadow public EntityClientPlayerMP thePlayer;

    @Inject(method = "runTick()V", at = @At("HEAD"))
    private void fmlForgeTickStart(CallbackInfo callbackInfo) {
        EnumSet<TickType> types = EnumSet.of(TickType.CLIENT);
        if (this.theWorld != null) types.add(TickType.WORLD);
        if (this.thePlayer != null) types.add(TickType.PLAYER);
        TickRegistry.dispatchStart(Side.CLIENT, types);
    }

    @Inject(method = "runTick()V", at = @At("RETURN"))
    private void fmlForgeTickEnd(CallbackInfo callbackInfo) {
        EnumSet<TickType> types = EnumSet.of(TickType.CLIENT);
        if (this.theWorld != null) types.add(TickType.WORLD);
        if (this.thePlayer != null) types.add(TickType.PLAYER);
        TickRegistry.dispatchEnd(Side.CLIENT, types);
    }
}
