package net.xiaoyu233.fml.reload.utils.id;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class IdStorage {
	public static final Map<IdType, Set<Integer>> RESERVED = new EnumMap<>(IdType.class);
	public static final Map<IdType, Set<Integer>> USED = new EnumMap<>(IdType.class);
	
	static {
		for (IdType type : IdType.values()) {
			RESERVED.put(type, ConcurrentHashMap.newKeySet());
			USED.put(type, ConcurrentHashMap.newKeySet());
		}
	}
}