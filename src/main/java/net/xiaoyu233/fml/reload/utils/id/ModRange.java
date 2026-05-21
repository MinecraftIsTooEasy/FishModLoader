package net.xiaoyu233.fml.reload.utils.id;

import javax.annotation.Nonnull;

public record ModRange(String modId, IdType type, int initial, int count) {

	@Nonnull
	@Override
	public String toString() {
		return modId + ":" + type.name() + "=" + initial + "," + count;
	}
}