package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.world.gen.feature.WorldGenMinable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(WorldGenMinable.class)
public abstract class WorldGenMinableMixin {

    @Shadow
    private int minableBlockId;

    @Shadow
    private int numberOfBlocks;

    @Shadow
    private int blockToReplace;

    @Unique
    private int minableBlockMeta = 0;

    @Unique
    public void fmlForgeInit(int id, int meta, int number, int target) {
        this.minableBlockId = id;
        this.numberOfBlocks = number;
        this.blockToReplace = target;
        this.minableBlockMeta = meta;
    }
}
