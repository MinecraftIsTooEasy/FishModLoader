package net.xiaoyu233.fml.reload.transform.fix.fix_nbt;

import net.minecraft.*;
import net.xiaoyu233.fml.FishModLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Collection;

@Mixin(value = ServerPlayer.class, priority = 999)
public abstract class ServerPlayerMixin extends EntityPlayer {
    public ServerPlayerMixin(World par1World, String par2Str) {
        super(par1World, par2Str);
    }

    /**
     * @author Debris
     * @reason fix
     */
    @Overwrite
    @SuppressWarnings("unchecked")
    public void readStatsFromNBT(NBTTagCompound par1NBTTagCompound) {
        Collection<?> tags = par1NBTTagCompound.getTags();
        for (Object o : tags) {
            NBTBase tag = (NBTBase) o;
            int id = Integer.parseInt(tag.getName());
            StatBase stat = StatList.getStat(id);
            if (stat == null) {
                continue;
            }
            try {
                if (StatList.isEitherZeroOrOne(stat)) {
                    this.stats.put(id, par1NBTTagCompound.getByte(tag.getName()));
                } else if (StatList.hasLongValue(stat)) {
                    this.stats.put(id, par1NBTTagCompound.getLong(tag.getName()));
                } else {
                    this.stats.put(id, par1NBTTagCompound.getInteger(tag.getName()));
                }
            } catch (Exception ignored) {
                FishModLoader.LOGGER.warn("error reading nbt tag {} for player {}", tag.getName(), this.getEntityName());
            }
        }
    }

}

