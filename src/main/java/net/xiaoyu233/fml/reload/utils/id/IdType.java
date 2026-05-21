package net.xiaoyu233.fml.reload.utils.id;

public enum IdType {
	BLOCK(2024, 4095),
	ITEM(3840, 31999),
	ENTITY(200, 32766),
	ENCHANTMENT(96, 255),
	POTION(24, 255),
	ACHIEVEMENT(136, 32766),
	BIOME(27, 255),
	DIMENSION(2, 127),
	WORLD_TYPE(3, 255),
	PACKET(134, 255),
	VILLAGER_PROFESSION(5, 255);
	
	public final int initial;
	public final int max;
	
	IdType(int start, int max) {
		this.initial = start;
		this.max = max;
	}
}