package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.item.EntityMinecartEmpty;
import net.minecraftforge.common.IMinecartCollisionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityMinecart.class)
public abstract class EntityMinecartBaseMixin {
    @Shadow public abstract int getMinecartType();

    @Unique
    public static float defaultMaxSpeedAirLateral = 0.4f;
    @Unique
    public static float defaultMaxSpeedAirVertical = -1f;
    @Unique
    public static double defaultDragAir = 0.94999998807907104D;
    @Unique
    protected boolean canUseRail = true;
    @Unique
    protected boolean canBePushed = true;
    @Unique
    private static IMinecartCollisionHandler collisionHandler = null;
    @Unique
    private float currentSpeedRail = 1.2f;
    @Unique
    protected float maxSpeedAirLateral = defaultMaxSpeedAirLateral;
    @Unique
    protected float maxSpeedAirVertical = defaultMaxSpeedAirVertical;
    @Unique
    protected double dragAir = defaultDragAir;

    @Unique
    public void moveMinecartOnRail(int x, int y, int z, double par4) {
        double d12 = ((net.minecraft.entity.Entity)(Object)this).motionX;
        double d13 = ((net.minecraft.entity.Entity)(Object)this).motionZ;

        if (((EntityMinecart)(Object)this).riddenByEntity != null) {
            d12 *= 0.75D;
            d13 *= 0.75D;
        }

        if (d12 < -par4) d12 = -par4;
        if (d12 > par4) d12 = par4;
        if (d13 < -par4) d13 = -par4;
        if (d13 > par4) d13 = par4;

        ((net.minecraft.entity.Entity)(Object)this).moveEntity(d12, 0.0D, d13);
    }

    @Unique
    public static IMinecartCollisionHandler getCollisionHandler() {
        return collisionHandler;
    }

    @Unique
    public static void setCollisionHandler(IMinecartCollisionHandler handler) {
        collisionHandler = handler;
    }

    @Unique
    public boolean canUseRail() {
        return canUseRail;
    }

    @Unique
    public void setCanUseRail(boolean use) {
        canUseRail = use;
    }

    @Unique
    public boolean shouldDoRailFunctions() {
        return true;
    }

    @Unique
    public boolean isPoweredCart() {
        return getMinecartType() == 2;
    }

    @Unique
    public boolean canBeRidden() {
        return ((EntityMinecart)(Object)this) instanceof EntityMinecartEmpty;
    }

    @Unique
    public float getMaxCartSpeedOnRail() {
        return 1.2f;
    }

    @Unique
    public final float getCurrentCartSpeedCapOnRail() {
        return currentSpeedRail;
    }

    @Unique
    public final void setCurrentCartSpeedCapOnRail(float value) {
        value = Math.min(value, getMaxCartSpeedOnRail());
        currentSpeedRail = value;
    }

    @Unique
    public float getMaxSpeedAirLateral() {
        return maxSpeedAirLateral;
    }

    @Unique
    public void setMaxSpeedAirLateral(float value) {
        maxSpeedAirLateral = value;
    }

    @Unique
    public float getMaxSpeedAirVertical() {
        return maxSpeedAirVertical;
    }

    @Unique
    public void setMaxSpeedAirVertical(float value) {
        maxSpeedAirVertical = value;
    }

    @Unique
    public double getDragAir() {
        return dragAir;
    }

    @Unique
    public void setDragAir(double value) {
        dragAir = value;
    }

    @Unique
    public double getSlopeAdjustment() {
        return 0.0078125D;
    }
}
