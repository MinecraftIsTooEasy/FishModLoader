package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.NetClientHandler;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IMixinGuiConnecting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(GuiConnecting.class)
public class GuiConnectingMixin implements IMixinGuiConnecting {
    @Shadow private NetClientHandler clientHandler;
    @Shadow private boolean cancelled;

    @Override
    public void forceTermination() {
        this.cancelled = true;
        this.clientHandler = null;
    }
}
