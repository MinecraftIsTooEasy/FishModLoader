package net.xiaoyu233.fml.reload.transform.forge_compat.accessor;

import net.minecraft.network.NetLoginHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NetLoginHandler.class)
public interface NetLoginHandlerAccessor {
    @Accessor("clientUsername")
    String getClientUsername();

    @Accessor("field_72544_i")
    boolean getField_72544_i();

    @Accessor("field_72544_i")
    void setField_72544_i(boolean value);
}
