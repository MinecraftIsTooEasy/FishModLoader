package net.xiaoyu233.fml.reload.transform.forge_compat.event;

import cpw.mods.fml.common.network.IPacketHandler;
import cpw.mods.fml.common.network.Player;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.INetworkManager;
import net.minecraft.network.NetServerHandler;
import net.minecraft.network.packet.Packet250CustomPayload;
import net.xiaoyu233.fml.FishModLoader;
import net.xiaoyu233.fml.forge.network.NetworkRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetServerHandler.class)
public abstract class ForgeServerPacketRouterMixin {
    @Shadow @Final public INetworkManager netManager;
    @Shadow public EntityPlayerMP playerEntity;

    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/packet/Packet250CustomPayload;)V", at = @At("HEAD"), cancellable = true)
    private void fmlForgeRouteServerCustomPayload(Packet250CustomPayload packet, CallbackInfo callbackInfo) {
        if (packet == null || packet.channel == null) return;
        IPacketHandler handler = NetworkRegistry.instance().getHandler(packet.channel);
        if (handler == null) return;
        try {
            handler.onPacketData(netManager, packet, (Player) playerEntity);
        } catch (Throwable thrown) {
            FishModLoader.LOGGER.error("Forge mod handler threw on channel {}", packet.channel, thrown);
        }
        callbackInfo.cancel();
    }
}
