package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.client.multiplayer.NetClientHandler;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Legacy v3 fix that used {@code @Overwrite} to replace
 * {@link NetClientHandler#handleMultiBlockChange(net.minecraft.network.packet.Packet97MultiBlockChange)}
 * with a 5-byte-per-block decoder.
 *
 * <p>Disabled in v4 — the new packet format is 6 bytes per block (it now
 * carries an {@code id_extra} byte so block ids can exceed 255), and the
 * v4 transformations live in {@code id_extend.NetClientHandlerMixin} and
 * {@code id_extend.PacketMultiBlockChangeMixin}. The old @Overwrite here
 * would shadow those and silently revert to the v3 5-byte layout, leaving
 * clients unable to decode the multi-block-change packets the server now
 * sends — which manifests as voids/missing chunks for joining players.
 */
@Mixin(NetClientHandler.class)
public class FixNetHandler {
	// intentionally empty — see class comment
}
