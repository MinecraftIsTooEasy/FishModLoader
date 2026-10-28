package net.xiaoyu233.fml.vulkan.mixin.opt.net;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.TcpConnection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.net.Socket;
import java.net.SocketException;

/**
 * WP-35 (OPT, WLD-01): TCP_NODELAY on game connections. MITE's {@link TcpConnection} constructor sets only the
 * SO_TIMEOUT and the traffic class; the ping sockets set TCP_NODELAY, the game sockets do not, so small packets wait
 * for Nagle's algorithm. Wraps the {@code setTrafficClass} call in {@code <init>}: the original call runs first, then
 * {@code setTcpNoDelay(true)} is applied. Wire protocol unchanged. Gated by {@code -Dfml.vulkan.opt.net=false}.
 */
@Mixin(TcpConnection.class)
public abstract class TcpConnectionNoDelayMixin {
    private static final Logger NODELAY_LOGGER = LogManager.getLogger("FML-Opt-Net");

    @WrapOperation(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Ljava/net/Socket;setTrafficClass(I)V")
    )
    private void fishmodloader$setTcpNoDelay(Socket socket, int trafficClass, Operation<Void> original) throws SocketException {
        original.call(socket, trafficClass);
        try {
            socket.setTcpNoDelay(true);
            NODELAY_LOGGER.debug("TCP_NODELAY set");
        } catch (SocketException ignored) {
        }
    }
}
