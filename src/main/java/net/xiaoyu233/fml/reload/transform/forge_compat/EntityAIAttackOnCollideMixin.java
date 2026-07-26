package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIAttackOnCollide;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityAIAttackOnCollide.class)
public abstract class EntityAIAttackOnCollideMixin {
    @Shadow
    private int field_75445_i;
    @Shadow
    private EntityCreature attacker;
    @Shadow
    private PathEntity entityPathEntity;
    @Shadow
    private double speedTowardsTarget;
    @Shadow
    private boolean longMemory;
    @Shadow
    private int attackTick;

    @Unique
    private int failedPathFindingPenalty;

    /**
     * @reason Forge adds pathfinding throttling and cooldown
     */
    @Overwrite
    public boolean shouldExecute() {
        EntityLivingBase entitylivingbase = this.attacker.getAttackTarget();

        if (entitylivingbase == null) {
            return false;
        } else if (!entitylivingbase.isEntityAlive()) {
            return false;
        } else {
            if (--this.field_75445_i <= 0) {
                this.entityPathEntity = this.attacker.getNavigator().getPathToEntityLiving(entitylivingbase);
                this.field_75445_i = 4 + this.attacker.getRNG().nextInt(7);
                return this.entityPathEntity != null;
            } else {
                return true;
            }
        }
    }

    /**
     * @reason Forge adds failed pathfinding penalty tracking
     */
    @Overwrite
    public void updateTask() {
        EntityLivingBase entitylivingbase = this.attacker.getAttackTarget();
        this.attacker.getLookHelper().setLookPositionWithEntity(entitylivingbase, 30.0F, 30.0F);

        if ((this.longMemory || this.attacker.getEntitySenses().canSee(entitylivingbase)) && --this.field_75445_i <= 0) {
            this.field_75445_i = this.failedPathFindingPenalty + 4 + this.attacker.getRNG().nextInt(7);
            this.attacker.getNavigator().tryMoveToEntityLiving(entitylivingbase, this.speedTowardsTarget);
            if (this.attacker.getNavigator().getPath() != null) {
                PathPoint finalPathPoint = this.attacker.getNavigator().getPath().getFinalPathPoint();
                if (finalPathPoint != null && entitylivingbase.getDistanceSq(finalPathPoint.xCoord, finalPathPoint.yCoord, finalPathPoint.zCoord) < 1) {
                    this.failedPathFindingPenalty = 0;
                } else {
                    this.failedPathFindingPenalty += 10;
                }
            } else {
                this.failedPathFindingPenalty += 10;
            }
        }

        this.attackTick = Math.max(this.attackTick - 1, 0);
    }
}
