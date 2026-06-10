package net.xiaoyu233.fml.forge.event;

import cpw.mods.fml.common.network.IPacketHandler;
import cpw.mods.fml.common.network.Player;
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

/**
 * Server-side counterpart to {@link ForgeClientPacketRouterMixin}: routes
 * incoming {@code Packet250CustomPayload} to whichever Forge {@link IPacketHandler}
 * registered for the channel.
 *
 * <p>The {@code Player} argument passed to the handler is the source
 * EntityPlayerMP cast to the marker interface. Forge 1.6 exposed
 * {@code playerEntity} on NetServerHandler — same field exists on MITE.
 */
@Mixin(NetServerHandler.class)
public abstract class ForgeServerPacketRouterMixin {

    @Shadow @Final public INetworkManager netManager;
    @Shadow public net.minecraft.entity.player.EntityPlayerMP playerEntity;

    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/packet/Packet250CustomPayload;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void fmlForgeRouteServerCustomPayload(Packet250CustomPayload packet, CallbackInfo callbackInfo) {
        if (packet == null || packet.channel == null) return;
        IPacketHandler handler = NetworkRegistry.instance().getHandler(packet.channel);
        if (handler == null) return;
        try {
            handler.onPacketData(netManager, packet, (Player) (Object) playerEntity);
        } catch (Throwable thrown) {
            FishModLoader.LOGGER.error("Forge mod handler threw on channel {}", packet.channel, thrown);
        }
        callbackInfo.cancel();
    }
}
