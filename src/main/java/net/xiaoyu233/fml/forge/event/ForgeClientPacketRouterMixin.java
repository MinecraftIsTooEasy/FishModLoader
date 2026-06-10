package net.xiaoyu233.fml.forge.event;

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

/**
 * Routes {@code Packet250CustomPayload} arriving at the client to the
 * Forge {@link IPacketHandler} registered for that channel.
 *
 * <p>Vanilla MITE still gets to run if no handler matches — this lets the
 * existing {@code MC|...} channels (e.g. {@code MC|TrSel}, {@code MC|Brand})
 * keep working untouched.
 */
@Mixin(NetClientHandler.class)
public abstract class ForgeClientPacketRouterMixin {

    @Shadow private INetworkManager netManager;

    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/packet/Packet250CustomPayload;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void fmlForgeRouteClientCustomPayload(Packet250CustomPayload packet, CallbackInfo callbackInfo) {
        if (packet == null || packet.channel == null) return;
        IPacketHandler handler = NetworkRegistry.instance().getHandler(packet.channel);
        if (handler == null) return;
        try {
            handler.onPacketData(netManager, packet, (Player) null);
        } catch (Throwable thrown) {
            FishModLoader.LOGGER.error("Forge mod handler threw on channel {}", packet.channel, thrown);
        }
        callbackInfo.cancel();
    }
}
