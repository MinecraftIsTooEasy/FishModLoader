package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.Packet56MapChunks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.Semaphore;

@Mixin(Packet56MapChunks.class)
public abstract class Packet56MapChunksMixin extends Packet {

    @Shadow
    private int[] chunkPostX;

    @Shadow
    private int[] chunkPosZ;

    @Shadow
    private int[] field_73588_b;

    @Shadow
    private byte[][] field_73584_f;

    @Shadow
    private byte[] chunkDataBuffer;

    @Shadow
    private int dataLength;

    @Shadow
    private boolean skyLightSent;

    @Unique
    private Semaphore deflateGate;

    @Unique
    private int maxLen = 0;

    @Unique
    private void deflate() {
        byte[] data = new byte[maxLen];
        int offset = 0;
        for (int x = 0; x < field_73584_f.length; x++) {
            System.arraycopy(field_73584_f[x], 0, data, offset, field_73584_f[x].length);
            offset += field_73584_f[x].length;
        }

        java.util.zip.Deflater deflater = new java.util.zip.Deflater(-1);
        try {
            deflater.setInput(data, 0, maxLen);
            deflater.finish();
            byte[] deflated = new byte[maxLen];
            this.dataLength = deflater.deflate(deflated);
            this.chunkDataBuffer = deflated;
        } finally {
            deflater.end();
        }
    }
}
