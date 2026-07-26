package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.util.Session;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Session.class)
public class SessionMixin {

    @Shadow
    private String username;

    @Shadow
    private String sessionId;
}
