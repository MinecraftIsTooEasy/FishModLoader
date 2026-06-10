package net.minecraftforge.event.entity;

import net.minecraft.entity.Entity;
import net.minecraftforge.event.Event;

public class EntityEvent extends Event
{
    public final Entity entity;

    public EntityEvent(Entity entity)
    {
        this.entity = entity;
    }

    public static class EntityConstructing extends EntityEvent
    {
        public EntityConstructing(Entity entity)
        {
            super(entity);
        }
    }

    public static class CanUpdate extends EntityEvent
    {
        // Default: allow update. Forge 1.6 semantics: a listener must
        // explicitly set this to false to prevent ticking. With the original
        // `false` default, WorldEventsMixin#fmlForgeOnUpdateEntity would
        // cancel every entity tick when no listener was registered, freezing
        // the world (no chunks generated, no packets sent — players appear
        // suspended in the void on join).
        public boolean canUpdate = true;
        public CanUpdate(Entity entity)
        {
            super(entity);
        }
    }

    public static class EnteringChunk extends EntityEvent
    {
        public int newChunkX;
        public int newChunkZ;
        public int oldChunkX;
        public int oldChunkZ;

        public EnteringChunk(Entity entity, int newChunkX, int newChunkZ, int oldChunkX, int oldChunkZ)
        {
            super(entity);
            this.newChunkX = newChunkX;
            this.newChunkZ = newChunkZ;
            this.oldChunkX = oldChunkX;
            this.oldChunkZ = oldChunkZ;
        }
    }
}
