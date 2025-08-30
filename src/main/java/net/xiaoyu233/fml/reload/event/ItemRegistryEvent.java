package net.xiaoyu233.fml.reload.event;

import net.minecraft.*;
import net.xiaoyu233.fml.api.block.IBlock;
import net.xiaoyu233.fml.api.item.IItem;

public class ItemRegistryEvent {
    public Item register(String namespace, String resourceLocation, String unlocalizedName, Item item, CreativeTabs tab) {
        ((IItem) item).setItemTextureName(((IItem) item).getTexturePrefix() + resourceLocation);
        item.setUnlocalizedName(unlocalizedName);
        ((IItem) item).setNamespace(namespace);
        item.setCreativeTab(tab);
        return item;
    }

    public Item register(String namespace, String resourceLocation, Item item, CreativeTabs tab) {
        ((IItem) item).setItemTextureName(((IItem) item).getTexturePrefix() + resourceLocation);
        item.setUnlocalizedName(resourceLocation);
        ((IItem) item).setNamespace(namespace);
        item.setCreativeTab(tab);
        return item;
    }

    public Item register(String namespace, Item item, CreativeTabs tab) {
        ((IItem) item).setNamespace(namespace);
        item.setCreativeTab(tab);
        return item;
    }

    public Item register(String namespace, String resourceLocation, Item item) {
        ((IItem) item).setItemTextureName(((IItem) item).getTexturePrefix() + resourceLocation);
        ((IItem) item).setNamespace(namespace);
        item.setUnlocalizedName(resourceLocation);
        return item;
    }

    public Item register(String namespace, String resourceLocation, String unlocalizedName, Item item) {
        ((IItem) item).setItemTextureName(((IItem) item).getTexturePrefix() + resourceLocation);
        ((IItem) item).setNamespace(namespace);
        item.setUnlocalizedName(unlocalizedName);
        return item;
    }

    public Item register(String namespace, Item item) {
        ((IItem) item).setNamespace(namespace);
        return item;
    }

    public void registerAnvil(String namespace, String resourceLocation, String unlocalizedName, BlockAnvil blockAnvil) {
        blockAnvil.setUnlocalizedName(unlocalizedName);
        if (!((IBlock) blockAnvil).hasNamespaceSet()) {
            ((IBlock) blockAnvil).setNamespace(namespace);
        }
        ((IBlock) blockAnvil).setBlockTextureName(resourceLocation);
        Item item = new ItemAnvilBlock(blockAnvil).setUnlocalizedName(unlocalizedName);
        ((IItem) item).setNamespace(namespace);
        item.setMaxStackSize(blockAnvil.getItemStackLimit());
    }

    public void registerAnvil(String namespace, String resourceLocation, BlockAnvil blockAnvil) {
        blockAnvil.setUnlocalizedName(resourceLocation);
        if (!((IBlock) blockAnvil).hasNamespaceSet()) {
            ((IBlock) blockAnvil).setNamespace(namespace);
        }
        ((IBlock) blockAnvil).setBlockTextureName(resourceLocation);
        Item item = new ItemAnvilBlock(blockAnvil).setUnlocalizedName(resourceLocation);
        ((IItem) item).setNamespace(namespace);
        item.setMaxStackSize(blockAnvil.getItemStackLimit());
    }

    public void registerAnvil(String namespace, BlockAnvil blockAnvil) {
        if (!((IBlock) blockAnvil).hasNamespaceSet()) {
            ((IBlock) blockAnvil).setNamespace(namespace);
        }
        Item item = new ItemAnvilBlock(blockAnvil);
        ((IItem) item).setNamespace(namespace);
        item.setMaxStackSize(blockAnvil.getItemStackLimit());
    }

    public void registerItemBlock(String namespace, String resourceLocation, String unlocalizedName, Block block) {
        block.setUnlocalizedName(unlocalizedName);
        if (!((IBlock) block).hasNamespaceSet()) {
            ((IBlock) block).setNamespace(namespace);
        }
        ((IBlock) block).setBlockTextureName(resourceLocation);
        Item item = new ItemBlock(block).setUnlocalizedName(unlocalizedName);
        ((IItem) item).setNamespace(namespace);
        item.setMaxStackSize(block.getItemStackLimit());
    }

    public void registerItemBlock(String namespace, String resourceLocation, Block block) {
        block.setUnlocalizedName(resourceLocation);
        if (!((IBlock) block).hasNamespaceSet()) {
            ((IBlock) block).setNamespace(namespace);
        }
        ((IBlock) block).setBlockTextureName(resourceLocation);
        Item item = new ItemBlock(block).setUnlocalizedName(resourceLocation);
        ((IItem) item).setNamespace(namespace);
        item.setMaxStackSize(block.getItemStackLimit());
    }

    public void registerItemBlock(String namespace, Block block) {
        if (!((IBlock) block).hasNamespaceSet()) {
            ((IBlock) block).setNamespace(namespace);
        }
        Item item = new ItemBlock(block);
        ((IItem) item).setNamespace(namespace);
        item.setMaxStackSize(block.getItemStackLimit());
    }
}
