package net.xiaoyu233.fml.api.item;

import net.xiaoyu233.fml.api.INamespaced;

public interface IItemStack extends INamespaced {
    default String getNamespace(){throw new AssertionError();}
}