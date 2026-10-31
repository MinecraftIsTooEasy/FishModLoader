package vkbench.mixin;

import net.minecraft.NetClientHandler;
import net.minecraft.Packet97MultiBlockChange;
import net.minecraft.WorldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vkbench.Bench;

/**
 * Dev only, OpenGL baseline only ({@code -Dfml.bench.build=baseline}): the baseline commit 2041277 crashes the client in
 * {@code NetClientHandler.handleMultiBlockChange(Packet97)}: FML's id_extend writes 6 byte entries (x, y, z, id high, id low, metadata), the patched reader of that
 * old commit then trips over packets that hold fewer entries than {@code num_blocks} and over chunks without skylight arrays ("Index 11 out of bounds for length 10",
 * "pending_skylight_updates is null"); a later FML commit replaced the whole thing by the {@code FixNetHandler} guard. The baseline gets a defensive reader with the
 * same semantics (5 or 6 byte entries, bounds checked, per block exception guard) so the traversal can run; every other build is untouched.
 */
@Mixin(NetClientHandler.class)
public abstract class BenchNetClientHandlerMixin {
    @Shadow
    private WorldClient worldClient;

    @Inject(method = "handleMultiBlockChange(Lnet/minecraft/Packet97MultiBlockChange;)V", at = @At("HEAD"), cancellable = true)
    private void vkbench$safeMultiBlockChange(Packet97MultiBlockChange packet, CallbackInfo ci) {
        if (!Bench.ENABLED || !Bench.BUILD.equals("baseline")) {
            return;
        }
        ci.cancel();
        byte[] bytes = packet.getBytes();
        if (bytes == null) {
            return;
        }
        int baseX = packet.chunk_x * 16;
        int baseZ = packet.chunk_z * 16;
        // the baseline's writer mixin (id_extend) is not applied (its injector signature is broken in that commit) while its reader mixin is: entries are the original
        // 5 bytes (x, y, z, id, metadata); 6 byte entries (x, y, z, id high, id low, metadata) are accepted too, the stride follows from the packet size
        int stride = packet.num_blocks > 0 && bytes.length >= packet.num_blocks * 6 ? 6 : 5;
        int n = Math.min(packet.num_blocks, bytes.length / stride);
        for (int i = 0; i < n; i++) {
            int o = i * stride;
            int x = baseX + (bytes[o] & 15);
            int y = bytes[o + 1] & 255;
            int z = baseZ + (bytes[o + 2] & 15);
            int id = stride == 6 ? ((bytes[o + 3] & 255) << 8) | (bytes[o + 4] & 255) : bytes[o + 3] & 255;
            int meta = bytes[o + stride - 1] & 15;
            try {
                this.worldClient.setBlockAndMetadataAndInvalidate(x, y, z, id, meta);
                if (this.worldClient.hasSkylight()) {
                    this.worldClient.getChunkFromBlockCoords(x, z).addPendingSkylightUpdate(x, y, z);
                }
            } catch (RuntimeException e) {
                // a chunk the client has not finished creating: the next chunk packet carries the block anyway
            }
        }
    }
}
