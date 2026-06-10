package net.minecraftforge.common;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import net.minecraft.entity.Entity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * STUB. The original ForgeChunkManager implements forced-chunk-loading via
 * cache files and Forge-patched WorldServer/Entity hooks that don't exist
 * on stock 1.6.4 MITE. Public API is preserved as no-ops; will be wired up
 * in stage 6 (system-level network/dimension).
 */
public class ForgeChunkManager {

    public static void setForcedChunkLoadingCallback(Object mod, LoadingCallback callback) {}

    public static int ticketCountAvailableFor(Object mod, World world) { return Integer.MAX_VALUE; }

    public static int ticketCountAvailableFor(String username) { return Integer.MAX_VALUE; }

    public static int playerTicketCount(String username) { return 0; }

    public static int getMaxTicketLengthFor(String modId) { return 25; }

    // -------- Static API: most calls become no-ops --------

    public static int getMaxChunkDepthFor(String modId)   { return 25; }

    public static Ticket requestTicket(Object mod, World world, Type type) {
        String modId = mod instanceof String ? (String) mod : mod.getClass().getName();
        return new Ticket(modId, type, world);
    }

    public static Ticket requestPlayerTicket(Object mod, String player, World world, Type type) {
        Ticket t = requestTicket(mod, world, type);
        try { t.player = UUID.nameUUIDFromBytes(player.getBytes()); } catch (Exception ignored) {}
        return t;
    }

    public static void releaseTicket(Ticket ticket) {}

    public static void forceChunk(Ticket ticket, ChunkCoordIntPair chunk) {}

    public static void unforceChunk(Ticket ticket, ChunkCoordIntPair chunk) {}

    public static void reorderChunk(Ticket ticket, ChunkCoordIntPair chunk) {}

    public static ImmutableSetMultimap<ChunkCoordIntPair, Ticket> getPersistentChunksFor(World world) {
        return ImmutableSetMultimap.of();
    }

    public static SetMultimap<ChunkCoordIntPair, Ticket> getActiveChunksFor(World world) {
        return HashMultimap.create();
    }

    public static List<Ticket> getPersistentChunksIterableFor(World world, java.util.Iterator<net.minecraft.world.chunk.Chunk> chunkIterator) {
        return Collections.emptyList();
    }

    // World load/unload hooks — called by FML internally; harmless no-ops.
    public static void loadWorld(World world) {}

    public static void unloadWorld(World world) {}

    public static void loadConfiguration() {}

    public static void captureConfig(java.io.File configDir) {}

    public static void saveWorld(World world) {}

    public enum Type { NORMAL, ENTITY }
    public interface LoadingCallback {
        void ticketsLoaded(List<Ticket> tickets, World world);
    }
    public interface OrderedLoadingCallback extends LoadingCallback {
        List<Ticket> ticketsLoaded(List<Ticket> tickets, World world, int maxTicketCount);
    }
    public interface PlayerOrderedLoadingCallback extends LoadingCallback {
        com.google.common.collect.ListMultimap<String, Ticket> playerTicketsLoaded(
                com.google.common.collect.ListMultimap<String, Ticket> tickets, World world);
    }

    public static class Ticket {
        public final World world;
        private final String modId;
        private final Type ticketType;
        Entity entity;
        UUID player;
        int maxDepth;

        Ticket(String modId, Type ticketType, World world) {
            this.modId = modId;
            this.ticketType = ticketType;
            this.world = world;
        }

        public String getModId()  { return modId; }
        public Type getType()     { return ticketType; }
        public Entity getEntity() { return entity; }
        public UUID getPlayerName() { return player; }
        public int getChunkListDepth() { return maxDepth; }
        public void setChunkListDepth(int depth) { this.maxDepth = depth; }
        public void bindEntity(Entity e) { this.entity = e; }
    }
}
