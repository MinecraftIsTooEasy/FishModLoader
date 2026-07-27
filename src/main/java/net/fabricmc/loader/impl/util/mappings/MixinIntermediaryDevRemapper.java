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

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.spongepowered.asm.mixin.transformer.ClassInfo;

import net.fabricmc.mappingio.tree.MappingTree;

public class MixinIntermediaryDevRemapper extends MixinRemapper {
	private static final String ambiguousName = "<ambiguous>"; // dummy value for ambiguous mappings - needs querying with additional owner and/or desc info

	private final Set<String> allPossibleClassNames = new HashSet<>();
	private final Map<String, String> nameFieldLookup = new HashMap<>();
	private final Map<String, String> nameMethodLookup = new HashMap<>();
	private final Map<String, String> nameDescFieldLookup = new HashMap<>();
	private final Map<String, String> nameDescMethodLookup = new HashMap<>();
	// Owner-specific maps: className -> (memberName -> mappedName)
	// These do NOT use descriptors, so they work correctly even when
	// named.tiny stores descriptors in notch format while runtime
	// class files use named-format descriptors.
	private final Map<String, Map<String, String>> ownerFieldMappings = new HashMap<>();
	private final Map<String, Map<String, String>> ownerMethodMappings = new HashMap<>();

	public MixinIntermediaryDevRemapper(MappingTree mappings, String from, String to) {
		super(mappings, mappings.getNamespaceId(from), mappings.getNamespaceId(to));

		for (MappingTree.ClassMapping classDef : mappings.getClasses()) {
			allPossibleClassNames.add(classDef.getName(from));
			allPossibleClassNames.add(classDef.getName(to));

			String fromName = classDef.getName(from);

			// Build owner-specific field map
			Map<String, String> fieldMap = new HashMap<>();
			for (MappingTree.FieldMapping field : classDef.getFields()) {
				String srcName = field.getName(from);
				String dstName = field.getName(to);
				fieldMap.put(srcName, dstName);
			}
			ownerFieldMappings.put(fromName, fieldMap);

			// Build owner-specific method map
			Map<String, String> methodMap = new HashMap<>();
			for (MappingTree.MethodMapping method : classDef.getMethods()) {
				String srcName = method.getName(from);
				String dstName = method.getName(to);
				methodMap.put(srcName, dstName);
			}
			ownerMethodMappings.put(fromName, methodMap);

			putMemberInLookup(fromId, toId, classDef.getFields(), nameFieldLookup, nameDescFieldLookup);
			putMemberInLookup(fromId, toId, classDef.getMethods(), nameMethodLookup, nameDescMethodLookup);
		}
	}

	private <T extends MappingTree.MemberMapping> void putMemberInLookup(int from, int to, Collection<T> descriptored, Map<String, String> nameMap, Map<String, String> nameDescMap) {
		for (T field : descriptored) {
			String nameFrom = field.getName(from);
			String descFrom = field.getDesc(from);
			String nameTo = field.getName(to);

			String prev = nameMap.putIfAbsent(nameFrom, nameTo);

			if (prev != null && prev != ambiguousName && !prev.equals(nameTo)) {
				nameDescMap.put(nameFrom, ambiguousName);
			}

			String key = getNameDescKey(nameFrom, descFrom);
			prev = nameDescMap.putIfAbsent(key, nameTo);

			if (prev != null && prev != ambiguousName && !prev.equals(nameTo)) {
				nameDescMap.put(key, ambiguousName);
			}
		}
	}

	private void throwAmbiguousLookup(String type, String name, String desc) {
		throw new RuntimeException("Ambiguous Mixin: " + type + " lookup " + name + " " + desc+" is not unique");
	}

	private String mapMethodNameInner(String owner, String name, String desc) {
		String result = super.mapMethodName(owner, name, desc);

		if (result.equals(name)) {
			String otherClass = unmap(owner);
			return super.mapMethodName(otherClass, name, unmapDesc(desc));
		} else {
			return result;
		}
	}

	private String mapFieldNameInner(String owner, String name, String desc) {
		String result = super.mapFieldName(owner, name, desc);

		if (result.equals(name)) {
			String otherClass = unmap(owner);
			return super.mapFieldName(otherClass, name, unmapDesc(desc));
		} else {
			return result;
		}
	}

	/**
	 * Try to resolve a field name using the owner-specific map (no descriptor needed).
	 * Returns {@code name} if no mapping is found.
	 */
	private String mapFieldNameByOwner(String owner, String name) {
		if (owner == null) return name;
		Map<String, String> fieldMap = ownerFieldMappings.get(owner);
		if (fieldMap != null) {
			String mapped = fieldMap.get(name);
			if (mapped != null && !mapped.equals(name)) {
				return mapped;
			}
		}
		return name;
	}

	/**
	 * Try to resolve a method name using the owner-specific map (no descriptor needed).
	 * Returns {@code name} if no mapping is found.
	 */
	private String mapMethodNameByOwner(String owner, String name) {
		if (owner == null) return name;
		Map<String, String> methodMap = ownerMethodMappings.get(owner);
		if (methodMap != null) {
			String mapped = methodMap.get(name);
			if (mapped != null && !mapped.equals(name)) {
				return mapped;
			}
		}
		return name;
	}

	@Override
	public String mapMethodName(String owner, String name, String desc) {
		// handle unambiguous values early
		if (owner == null || allPossibleClassNames.contains(owner)) {
			String newName;

			if (desc == null) {
				newName = nameMethodLookup.get(name);
			} else {
				newName = nameDescMethodLookup.get(getNameDescKey(name, desc));
			}

			if (newName != null) {
				if (newName == ambiguousName) {
					if (owner == null) {
						throwAmbiguousLookup("method", name, desc);
					}
					// Fall through to owner-specific lookup
				} else {
					return newName;
				}
			}

			// Owner-specific lookup (no descriptor needed, handles notch/named mismatch)
			if (owner != null) {
				String mapped = mapMethodNameByOwner(owner, name);
				if (!mapped.equals(name)) {
					return mapped;
				}
			}

			if (owner == null) {
				return name;
			} else {
				// FIXME: this kind of namespace mixing shouldn't happen..
				// TODO: this should not repeat more than once
				String unmapOwner = unmap(owner);
				String unmapDesc = unmapDesc(desc);

				if (!unmapOwner.equals(owner) || !unmapDesc.equals(desc)) {
					return mapMethodName(unmapOwner, name, unmapDesc);
				}
				// else: fall through to ClassInfo hierarchy walk below
				// (handles inherited methods from parent classes)
			}
		}

		ClassInfo classInfo = ClassInfo.forName(map(owner));

		if (classInfo == null) { // unknown class?
			return name;
		}

		Queue<ClassInfo> queue = new ArrayDeque<>();

		do {
			String ownerO = unmap(classInfo.getName());
			String s;

			if (!(s = mapMethodNameInner(ownerO, name, desc)).equals(name)) {
				return s;
			}

			if (classInfo.getSuperName() != null && !classInfo.getSuperName().startsWith("java/")) {
				ClassInfo cSuper = classInfo.getSuperClass();

				if (cSuper != null) {
					queue.add(cSuper);
				}
			}

			for (String itf : classInfo.getInterfaces()) {
				if (itf.startsWith("java/")) {
					continue;
				}

				ClassInfo cItf = ClassInfo.forName(itf);

				if (cItf != null) {
					queue.add(cItf);
				}
			}
		} while ((classInfo = queue.poll()) != null);

		return name;
	}

	@Override
	public String mapFieldName(String owner, String name, String desc) {
		// handle unambiguous values early
		if (owner == null || allPossibleClassNames.contains(owner)) {
			String newName = nameDescFieldLookup.get(getNameDescKey(name, desc));

			if (newName != null) {
				if (newName == ambiguousName) {
					if (owner == null) {
						throwAmbiguousLookup("field", name, desc);
					}
					// Fall through to owner-specific lookup
				} else {
					return newName;
				}
			}

			// Owner-specific lookup (no descriptor needed, handles notch/named mismatch)
			if (owner != null) {
				String mapped = mapFieldNameByOwner(owner, name);
				if (!mapped.equals(name)) {
					return mapped;
				}
			}

			if (owner == null) {
				return name;
			} else {
				// FIXME: this kind of namespace mixing shouldn't happen..
				// TODO: this should not repeat more than once
				String unmapOwner = unmap(owner);
				String unmapDesc = unmapDesc(desc);

				if (!unmapOwner.equals(owner) || !unmapDesc.equals(desc)) {
					return mapFieldName(unmapOwner, name, unmapDesc);
				}
				// else: fall through to ClassInfo hierarchy walk below
				// (handles inherited fields like fontRenderer from GuiScreen)
			}
		}

		ClassInfo c = ClassInfo.forName(map(owner));

		while (c != null) {
			String nextOwner = unmap(c.getName());
			String s = mapFieldNameInner(nextOwner, name, desc);

			if (!s.equals(name)) {
				return s;
			}

			if (c.getSuperName() == null || c.getSuperName().startsWith("java/")) {
				break;
			}

			c = c.getSuperClass();
		}

		return name;
	}

	private static String getNameDescKey(String name, String descriptor) {
		return name+ ";;" + descriptor;
	}
}
