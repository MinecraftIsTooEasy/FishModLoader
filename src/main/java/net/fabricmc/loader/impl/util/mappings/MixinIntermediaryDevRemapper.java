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

import net.fabricmc.mappingio.tree.MappingTree;
import org.spongepowered.asm.mixin.extensibility.IRemapper;

import java.util.Objects;

/**
 * Mixin {@link IRemapper} backed by a mapping-io {@link MappingTree}.
 *
 * <p>In a development environment mods ship refmaps written against one
 * namespace (typically {@code intermediary}) while the game actually runs in
 * another (typically {@code named}). Mixin resolves its {@code @At}/{@code @Inject}
 * targets through the registered remappers, so this class bridges the two.
 *
 * <p>Lookups are descriptor-aware where possible and fall back to a
 * name-only search, because refmap descriptors are themselves written in the
 * source namespace and may not match after partial remapping.
 */
public final class MixinIntermediaryDevRemapper implements IRemapper {
	private final MappingTree mappings;
	private final int fromId;
	private final int toId;

	public MixinIntermediaryDevRemapper(MappingTree mappings, String from, String to) {
		this.mappings = Objects.requireNonNull(mappings, "mappings");
		this.fromId = namespaceId(mappings, Objects.requireNonNull(from, "from"));
		this.toId = namespaceId(mappings, Objects.requireNonNull(to, "to"));
	}

	private static int namespaceId(MappingTree tree, String namespace) {
		if (namespace.equals(tree.getSrcNamespace())) return MappingTree.SRC_NAMESPACE_ID;

		int id = tree.getDstNamespaces().indexOf(namespace);
		if (id < 0) throw new IllegalArgumentException("Unknown namespace: " + namespace);
		return id;
	}

	private String name(MappingTree.ElementMapping element) {
		if (element == null) return null;
		String mapped = element.getName(toId);
		return mapped != null && !mapped.isEmpty() ? mapped : null;
	}

	/** Resolve the source-namespace internal name of a class given in the 'from' namespace. */
	private String srcClassName(String owner) {
		if (owner == null) return null;
		if (fromId == MappingTree.SRC_NAMESPACE_ID) return owner;

		MappingTree.ClassMapping cls = mappings.getClass(owner, fromId);
		return cls != null ? cls.getSrcName() : owner;
	}

	@Override
	public String mapMethodName(String owner, String name, String desc) {
		if (owner == null || name == null) return name;

		String srcOwner = srcClassName(owner);
		MappingTree.ClassMapping cls = mappings.getClass(srcOwner);
		if (cls == null) return name;

		// Descriptor-aware lookup first.
		if (desc != null) {
			MappingTree.MethodMapping exact = cls.getMethod(name, desc, fromId);
			String mapped = name(exact);
			if (mapped != null) return mapped;
		}

		// Fall back to matching on name alone -- refmap descriptors are in the
		// source namespace and may not line up.
		for (MappingTree.MethodMapping method : cls.getMethods()) {
			if (name.equals(method.getName(fromId))) {
				String mapped = name(method);
				if (mapped != null) return mapped;
			}
		}

		return name;
	}

	@Override
	public String mapFieldName(String owner, String name, String desc) {
		if (owner == null || name == null) return name;

		String srcOwner = srcClassName(owner);
		MappingTree.ClassMapping cls = mappings.getClass(srcOwner);
		if (cls == null) return name;

		if (desc != null) {
			MappingTree.FieldMapping exact = cls.getField(name, desc, fromId);
			String mapped = name(exact);
			if (mapped != null) return mapped;
		}

		for (MappingTree.FieldMapping field : cls.getFields()) {
			if (name.equals(field.getName(fromId))) {
				String mapped = name(field);
				if (mapped != null) return mapped;
			}
		}

		return name;
	}

	@Override
	public String map(String typeName) {
		return mapClass(typeName, fromId, toId);
	}

	@Override
	public String unmap(String typeName) {
		return mapClass(typeName, toId, fromId);
	}

	private String mapClass(String typeName, int srcId, int dstId) {
		if (typeName == null) return null;

		MappingTree.ClassMapping cls = mappings.getClass(typeName, srcId);
		if (cls == null) return typeName;

		String mapped = cls.getName(dstId);
		return mapped != null && !mapped.isEmpty() ? mapped : typeName;
	}

	@Override
	public String mapDesc(String desc) {
		return mapDescriptor(desc, fromId, toId);
	}

	@Override
	public String unmapDesc(String desc) {
		return mapDescriptor(desc, toId, fromId);
	}

	/**
	 * Rewrite every {@code L<internal-name>;} occurrence in a type descriptor.
	 * Primitives, array prefixes and parentheses pass through untouched.
	 */
	private String mapDescriptor(String desc, int srcId, int dstId) {
		if (desc == null || desc.indexOf('L') < 0) return desc;

		StringBuilder out = new StringBuilder(desc.length());
		int pos = 0;

		while (pos < desc.length()) {
			char c = desc.charAt(pos);

			if (c != 'L') {
				out.append(c);
				pos++;
				continue;
			}

			int end = desc.indexOf(';', pos);
			if (end < 0) { // malformed; emit the remainder verbatim
				out.append(desc, pos, desc.length());
				break;
			}

			String internal = desc.substring(pos + 1, end);
			out.append('L').append(mapClass(internal, srcId, dstId)).append(';');
			pos = end + 1;
		}

		return out.toString();
	}
}
