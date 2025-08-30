package net.xiaoyu233.fml.reload.transform.fix.fix_nbt;

import net.minecraft.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

@Mixin(value = NBTTagCompound.class, priority = 999)
public abstract class NBTTagCompoundMixin {
    @Shadow
    private Map<?, ?> tagMap;

    @Shadow
    protected abstract CrashReport createCrashReport(String par1Str, int par2, ClassCastException par3ClassCastException);

    /**
     * @author Debris
     * @reason make safe
     */
    @Overwrite
    public byte getByte(String par1Str) {
        try {
            if (this.tagMap.containsKey(par1Str) && this.tagMap.get(par1Str) instanceof NBTTagByte nbtTagByte) {
                return nbtTagByte.data;
            } else {
                return 0;
            }
        } catch (ClassCastException var3) {
            throw new ReportedException(this.createCrashReport(par1Str, 1, var3));
        }
    }

    /**
     * @author Debris
     * @reason make safe
     */
    @Overwrite
    public long getLong(String par1Str) {
        try {
            if (this.tagMap.containsKey(par1Str) && this.tagMap.get(par1Str) instanceof NBTTagLong nbtTagLong) {
                return nbtTagLong.data;
            } else {
                return 0L;
            }
        } catch (ClassCastException var3) {
            throw new ReportedException(this.createCrashReport(par1Str, 1, var3));
        }
    }

    /**
     * @author Debris
     * @reason make safe
     */
    @Overwrite
    public int getInteger(String par1Str) {
        try {
            if (this.tagMap.containsKey(par1Str) && this.tagMap.get(par1Str) instanceof NBTTagInt nbtTagInt) {
                return nbtTagInt.data;
            } else {
                return 0;
            }
        } catch (ClassCastException var3) {
            throw new ReportedException(this.createCrashReport(par1Str, 1, var3));
        }
    }
}
