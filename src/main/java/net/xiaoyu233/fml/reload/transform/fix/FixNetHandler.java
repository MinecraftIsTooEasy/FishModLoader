package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.NetClientHandler;
import net.minecraft.Packet97MultiBlockChange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps {@code handleMultiBlockChange} from reading past the end of a packet whose byte array holds fewer entries than
 * {@code num_blocks} (crash "Index N out of bounds for length M").
 * <p>
 * This used to be a private {@code @Overwrite} of the whole method (it fails under Mixin 0.8.7: "cannot reduce visibility of PUBLIC
 * target method") that also decoded the vanilla 5 byte entries. That decoding drops the extended block ids and clashes with
 * {@code id_extend.NetClientHandlerMixin} / {@code PacketMultiBlockChangeMixin}, which use 6 byte entries
 * (x, y, z, id high, id low, metadata), so the guard is now a HEAD injection that works together with them.
 */
@Mixin(NetClientHandler.class)
public class FixNetHandler {
    @Inject(method = "handleMultiBlockChange(Lnet/minecraft/Packet97MultiBlockChange;)V", at = @At("HEAD"), cancellable = true)
    private void fml$guardMultiBlockChange(Packet97MultiBlockChange packet, CallbackInfo ci) {
        byte[] bytes = packet.getBytes();
        if (bytes == null) {
            ci.cancel();
            return;
        }
        packet.num_blocks = Math.min(packet.num_blocks, bytes.length / 6);
    }
}
