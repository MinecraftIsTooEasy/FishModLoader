package net.xiaoyu233.fml.reload.event;

import net.minecraft.ServerPlayer;

public record PlayerLoggedInEvent(ServerPlayer player) {
    /**
     * The accessor name of FML 3.x. Mods compiled against 3.x (e.g. Twilight Forest's login handler) still call it, and a missing method
     * is a {@link NoSuchMethodError} that takes the server thread down when the player joins.
     *
     * @deprecated use {@link #player()}
     */
    @Deprecated(since = "4.0.0")
    public ServerPlayer getPlayer() {
        return this.player;
    }
}
