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
    private byte[] chunkData;

    @Shadow
    private int tempLength;

    @Shadow
    private byte[] compressedChunkData;

    @Unique
    private Semaphore deflateGate;

    @Unique
    private void deflate() {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(-1);
        try {
            deflater.setInput(compressedChunkData, 0, compressedChunkData.length);
            deflater.finish();
            byte[] deflated = new byte[compressedChunkData.length];
            this.tempLength = deflater.deflate(deflated);
            this.chunkData = deflated;
        } finally {
            deflater.end();
        }
    }
}
