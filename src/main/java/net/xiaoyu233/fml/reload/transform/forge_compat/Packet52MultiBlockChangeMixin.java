package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.network.packet.Packet52MultiBlockChange;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Packet52MultiBlockChange.class)
public abstract class Packet52MultiBlockChangeMixin {
    // Patches modify constructor to use ForgeDummyContainer.clumpingThreshold instead of hardcoded 64
}
