/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl.util.mappings;

import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor;

import java.io.IOException;

/**
 * Strips mapping data the loader has no use for at runtime.
 *
 * <p>Method parameter names, local variable names and comments are only
 * relevant for tooling/decompilation. Dropping them keeps the in-memory
 * {@code MemoryMappingTree} noticeably smaller, which matters because the
 * whole tree is retained for the lifetime of the process.
 */
public final class FilteringMappingVisitor extends ForwardingMappingVisitor {
	public FilteringMappingVisitor(MappingVisitor next) {
		super(next);
	}

	@Override
	public boolean visitMethodArg(int argPosition, int lvIndex, String srcName) throws IOException {
		return false;
	}

	@Override
	public boolean visitMethodVar(int lvtRowIndex, int lvIndex, int startOpIdx, int endOpIdx,
			String srcName) throws IOException {
		return false;
	}

	@Override
	public void visitComment(MappedElementKind targetKind, String comment) throws IOException {
		// no-op: comments are not needed at runtime
	}
}
