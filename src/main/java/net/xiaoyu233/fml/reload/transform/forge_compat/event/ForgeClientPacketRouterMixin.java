package net.xiaoyu233.fml.reload.transform.forge_compat.event;

import cpw.mods.fml.common.network.IPacketHandler;
import cpw.mods.fml.common.network.Player;
import net.minecraft.client.multiplayer.NetClientHandler;
import net.minecraft.network.INetworkManager;
import net.minecraft.network.packet.Packet250CustomPayload;
import net.xiaoyu233.fml.FishModLoader;
import net.xiaoyu233.fml.forge.network.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetClientHandler.class)
public abstract class ForgeClientPacketRouterMixin {
    @Shadow private INetworkManager netManager;

    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/packet/Packet250CustomPayload;)V", at = @At("HEAD"), cancellable = true)
    private void fmlForgeRouteClientCustomPayload(Packet250CustomPayload packet, CallbackInfo callbackInfo) {
        if (packet == null || packet.channel == null) return;
        IPacketHandler handler = NetworkRegistry.instance().getHandler(packet.channel);
        if (handler == null) return;
        try {
            handler.onPacketData(netManager, packet, null);
        } catch (Throwable thrown) {
            FishModLoader.LOGGER.error("Forge mod handler threw on channel {}", packet.channel, thrown);
        }
        callbackInfo.cancel();
    }
}
