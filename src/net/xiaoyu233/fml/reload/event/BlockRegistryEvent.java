package net.xiaoyu233.fml.reload.event;

import net.minecraft.Block;
import net.xiaoyu233.fml.api.block.IBlock;

public class BlockRegistryEvent {
    public void registerBlock(String namespace, String resourceLocation, Block block) {
        block.setUnlocalizedName(resourceLocation);
        ((IBlock) block).setNamespace(namespace);
        ((IBlock) block).setBlockTextureName(resourceLocation);
    }

    public void registerBlock(String namespace, String resourceLocation, String unlocalizedName, Block block) {
        block.setUnlocalizedName(resourceLocation);
        ((IBlock) block).setNamespace(namespace);
        ((IBlock) block).setBlockTextureName(resourceLocation);
    }


    public void registerBlock(String namespace, Block block) {
        ((IBlock) block).setNamespace(namespace);
    }
}
