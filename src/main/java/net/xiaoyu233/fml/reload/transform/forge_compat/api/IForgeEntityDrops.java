package net.xiaoyu233.fml.reload.transform.forge_compat.api;

import net.minecraft.entity.item.EntityItem;

import java.util.ArrayList;

/**
 * Accesses Forge drop-capture state added once to Entity by EntityMixin.
 * Subclass mixins use this interface instead of shadowing mixin-added fields,
 * preserving one shared state throughout the entity inheritance chain.
 */
public interface IForgeEntityDrops {
    boolean fmlIsCapturingDrops();

    void fmlSetCapturingDrops(boolean capturing);

    ArrayList<EntityItem> fmlGetCapturedDrops();
}
