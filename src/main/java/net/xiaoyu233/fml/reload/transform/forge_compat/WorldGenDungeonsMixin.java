package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.gen.feature.WorldGenDungeons;
import net.minecraftforge.common.DungeonHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Random;

@Mixin(WorldGenDungeons.class)
public abstract class WorldGenDungeonsMixin {

    @Unique
    private String pickMobSpawner(Random par1Random) {
        return DungeonHooks.getRandomDungeonMob(par1Random);
    }
}
