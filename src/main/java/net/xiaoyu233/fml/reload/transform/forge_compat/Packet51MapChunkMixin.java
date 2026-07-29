package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.network.packet.Packet51MapChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.Semaphore;

@Mixin(Packet51MapChunk.class)
public abstract class Packet51MapChunkMixin {

    @Shadow
    private int xCh;

    @Shadow
    private int zCh;

    @Shadow
    private int yChMax;

    @Shadow
    private int yChMin;

    @Shadow
    private boolean includeInitialize;

    @Shadow
    private byte[] compressed_chunk_data;

    @Shadow
    private byte[] uncompressed_chunk_data;

    @Shadow
    private int compressed_chunk_data_length;

    @Unique
    private Semaphore deflateGate;

    @Unique
    private void deflate() {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(-1);
        try {
            // MITE semantics (verified against writePacketData in mite-named.jar):
            //   uncompressed_chunk_data      = raw input, its length is written as the
            //                                  uncompressed size
            //   compressed_chunk_data        = deflate output, written to the wire
            //   compressed_chunk_data_length = number of valid bytes in the output
            deflater.setInput(uncompressed_chunk_data, 0, uncompressed_chunk_data.length);
            deflater.finish();
            byte[] deflated = new byte[uncompressed_chunk_data.length];
            this.compressed_chunk_data_length = deflater.deflate(deflated);
            this.compressed_chunk_data = deflated;
        } finally {
            deflater.end();
        }
    }
}
