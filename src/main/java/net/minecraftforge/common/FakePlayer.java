package net.minecraftforge.common;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.INetworkManager;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Forge 1.6.4 FakePlayer.
 *
 * <p>EntityPlayer in stock 1.6.4 MITE has one abstract method
 * ({@code getNetManager}) and inherits {@link net.minecraft.command.ICommandSender}'s
 * {@code getPlayerCoordinates}. We satisfy both: a fake player has no
 * connection and stands at {@code (0, 0, 0)} unless someone moves it.
 *
 * <p>Constructor mirrors {@link EntityPlayer}'s {@code (World, String)} so
 * the {@code username} field lands on super.
 */
public class FakePlayer extends EntityPlayer {

    public FakePlayer(World world, String name) {
        super(world, name);
    }

    @Override
    public INetworkManager getNetManager() {
        return null;
    }

    @Override
    public ChunkCoordinates getPlayerCoordinates() {
        return new ChunkCoordinates((int) posX, (int) posY, (int) posZ);
    }

    @Override
    public boolean canCommandSenderUseCommand(int permissionLevel, String commandName) {
        // Fake players have no operator privileges by default. Mods that need
        // a privileged fake player can subclass and return true.
        return false;
    }

    @Override
    public void sendChatToPlayer(net.minecraft.util.ChatMessageComponent component) {
        // Fake players have no chat sink — silently swallow.
    }
}
