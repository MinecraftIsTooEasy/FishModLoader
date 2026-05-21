package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.NetClientHandler;
import net.minecraft.Packet97MultiBlockChange;
import net.minecraft.WorldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NetClientHandler.class)
public class FixNetHandler {
	@Shadow private WorldClient worldClient;
	
	@Overwrite
	private void handleMultiBlockChange(Packet97MultiBlockChange packet) {
		byte[] bytes = packet.getBytes();
		if (bytes == null) return;
		int actual = bytes.length;
		int safeBlocks = Math.min(packet.num_blocks, actual / 5);
		
		int base_x = packet.chunk_x * 16;
		int base_z = packet.chunk_z * 16;
		for (int i = 0; i < safeBlocks; i++) {
			int offset = i * 5;
			int x = base_x + (bytes[offset] & 255);
			int y = bytes[offset + 1] & 255;
			int z = base_z + (bytes[offset + 2] & 255);
			int block_id = bytes[offset + 3] & 255;
			int meta = bytes[offset + 4] & 255;
			this.worldClient.setBlockAndMetadataAndInvalidate(x, y, z, block_id, meta);
			if (this.worldClient.hasSkylight()) {
				this.worldClient.getChunkFromBlockCoords(x, z).addPendingSkylightUpdate(x, y, z);
			}
		}
	}
}
