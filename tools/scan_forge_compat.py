#!/usr/bin/env python3
"""
Forge mod ↔ MITE compatibility scanner.

Scans every method/field reference inside a Forge mod jar and classifies it
against an unpacked MITE jar:
  - GREEN  : owner class exists in MITE *and* the exact (name, descriptor)
             pair is present (after applying the supplied SRG → MCP name table).
  - YELLOW : owner class exists, a same-name method/field exists, but with a
             different descriptor. Possibly recoverable via wrapper/redirect.
  - RED    : owner class does not exist in MITE, or there is no name match
             at all. Needs a stub or this code path is dead-on-MITE.

The SRG → MCP table is optional. If absent, the tool just reports SRG names
verbatim — useful for getting a baseline of how much remapping work remains.

Usage:
    python3 scan_forge_compat.py \\
        --mod    /path/to/worldedit-forge-mc1.6.4-6.0-alpha-01.jar \\
        --mite   /path/to/unpacked-mite/             # directory of .class files
        [--srg-table /path/to/forge-mcp-1.6.4.tiny]  # optional, tiny v2

Output: a text report on stdout, plus three CSVs next to the mod jar:
    <mod>.green.csv  <mod>.yellow.csv  <mod>.red.csv
"""

from __future__ import annotations

import argparse
import csv
import os
import struct
import sys
import zipfile
from collections import defaultdict
from dataclasses import dataclass
from typing import Dict, Iterable, List, Optional, Set, Tuple

# ----- minimal class file parser (no deps) -----------------------------------

CONSTANT_Utf8 = 1
CONSTANT_Integer = 3
CONSTANT_Float = 4
CONSTANT_Long = 5
CONSTANT_Double = 6
CONSTANT_Class = 7
CONSTANT_String = 8
CONSTANT_Fieldref = 9
CONSTANT_Methodref = 10
CONSTANT_InterfaceMethodref = 11
CONSTANT_NameAndType = 12
CONSTANT_MethodHandle = 15
CONSTANT_MethodType = 16
CONSTANT_InvokeDynamic = 18

ACC_STATIC = 0x0008


@dataclass
class MethodInfo:
    access: int
    name: str
    desc: str


@dataclass
class FieldInfo:
    access: int
    name: str
    desc: str


@dataclass
class ClassInfo:
    name: str        # internal name e.g. net/minecraft/server/MinecraftServer
    superclass: Optional[str]
    interfaces: List[str]
    methods: List[MethodInfo]
    fields: List[FieldInfo]


def parse_class(data: bytes) -> Optional[ClassInfo]:
    """Parse a .class file and return ClassInfo, or None if the bytes don't
    look like a class file."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        return None
    pos = 8
    cp_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    cp: List = [None] * cp_count
    i = 1
    while i < cp_count:
        tag = data[pos]
        pos += 1
        if tag == CONSTANT_Utf8:
            ln = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            cp[i] = ("u", data[pos:pos + ln].decode("utf-8", "replace"))
            pos += ln
        elif tag in (CONSTANT_Integer, CONSTANT_Float):
            cp[i] = (tag, 0)
            pos += 4
        elif tag in (CONSTANT_Long, CONSTANT_Double):
            cp[i] = (tag, 0)
            pos += 8
            i += 1  # 64-bit slots take two cp entries
        elif tag in (CONSTANT_Class, CONSTANT_String, CONSTANT_MethodType):
            cp[i] = (tag, struct.unpack_from(">H", data, pos)[0])
            pos += 2
        elif tag in (
            CONSTANT_Fieldref,
            CONSTANT_Methodref,
            CONSTANT_InterfaceMethodref,
            CONSTANT_NameAndType,
            CONSTANT_InvokeDynamic,
        ):
            cp[i] = (tag, struct.unpack_from(">HH", data, pos))
            pos += 4
        elif tag == CONSTANT_MethodHandle:
            cp[i] = (tag, (data[pos], struct.unpack_from(">H", data, pos + 1)[0]))
            pos += 3
        else:
            return None
        i += 1

    def utf(idx: int) -> str:
        e = cp[idx]
        return e[1] if e and e[0] == "u" else ""

    def cls_name(idx: int) -> str:
        return utf(cp[idx][1])

    pos += 2  # access flags
    this_class = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    super_class = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    iface_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    interfaces = []
    for _ in range(iface_count):
        ifc = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        interfaces.append(cls_name(ifc))

    fields: List[FieldInfo] = []
    fc = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(fc):
        access = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        n_idx = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        d_idx = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        ac = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        for _ in range(ac):
            pos += 2
            al = struct.unpack_from(">I", data, pos)[0]
            pos += 4 + al
        fields.append(FieldInfo(access, utf(n_idx), utf(d_idx)))

    methods: List[MethodInfo] = []
    mc = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(mc):
        access = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        n_idx = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        d_idx = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        ac = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        for _ in range(ac):
            pos += 2
            al = struct.unpack_from(">I", data, pos)[0]
            pos += 4 + al
        methods.append(MethodInfo(access, utf(n_idx), utf(d_idx)))

    return ClassInfo(
        name=cls_name(this_class),
        superclass=cls_name(super_class) if super_class else None,
        interfaces=interfaces,
        methods=methods,
        fields=fields,
    )


def collect_refs(data: bytes) -> Tuple[Set[Tuple[str, str, str]], Set[Tuple[str, str, str]]]:
    """Return (method_refs, field_refs) referenced by this class.

    Each ref is (owner_internal_name, name, descriptor)."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        return set(), set()
    pos = 8
    cp_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    cp: List = [None] * cp_count
    i = 1
    while i < cp_count:
        tag = data[pos]
        pos += 1
        if tag == CONSTANT_Utf8:
            ln = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            cp[i] = ("u", data[pos:pos + ln].decode("utf-8", "replace"))
            pos += ln
        elif tag in (CONSTANT_Integer, CONSTANT_Float):
            cp[i] = (tag, 0)
            pos += 4
        elif tag in (CONSTANT_Long, CONSTANT_Double):
            cp[i] = (tag, 0)
            pos += 8
            i += 1
        elif tag in (CONSTANT_Class, CONSTANT_String, CONSTANT_MethodType):
            cp[i] = (tag, struct.unpack_from(">H", data, pos)[0])
            pos += 2
        elif tag in (
            CONSTANT_Fieldref,
            CONSTANT_Methodref,
            CONSTANT_InterfaceMethodref,
            CONSTANT_NameAndType,
            CONSTANT_InvokeDynamic,
        ):
            cp[i] = (tag, struct.unpack_from(">HH", data, pos))
            pos += 4
        elif tag == CONSTANT_MethodHandle:
            cp[i] = (tag, (data[pos], struct.unpack_from(">H", data, pos + 1)[0]))
            pos += 3
        else:
            return set(), set()
        i += 1

    def utf(idx: int) -> str:
        e = cp[idx]
        return e[1] if e and e[0] == "u" else ""

    methods: Set[Tuple[str, str, str]] = set()
    fields: Set[Tuple[str, str, str]] = set()
    for entry in cp:
        if entry is None:
            continue
        tag = entry[0]
        if tag in (CONSTANT_Methodref, CONSTANT_InterfaceMethodref, CONSTANT_Fieldref):
            cls_idx, nat_idx = entry[1]
            cls_name = utf(cp[cls_idx][1])
            nat = cp[nat_idx]
            if not nat or nat[0] != CONSTANT_NameAndType:
                continue
            n_idx, t_idx = nat[1]
            target = methods if tag in (CONSTANT_Methodref, CONSTANT_InterfaceMethodref) else fields
            target.add((cls_name, utf(n_idx), utf(t_idx)))
    return methods, fields


# ----- SRG → MCP table loader -----------------------------------------------

def load_srg_table(path: str) -> Tuple[Dict[str, str], Dict[str, str]]:
    """Load a tiny v2 file. Returns (method_srg_to_name, field_srg_to_name).

    The mapping direction we need is: 'official' column contains SRG name
    (func_*/field_*), 'named' column contains the human-readable name.
    Standard MCP-derived tiny files are in this orientation."""
    methods: Dict[str, str] = {}
    fields: Dict[str, str] = {}
    if not path or not os.path.isfile(path):
        return methods, fields
    with open(path, "r", encoding="utf-8") as f:
        header = f.readline().strip().split("\t")
        if not header or header[0] != "tiny":
            print(f"WARN: {path} is not tiny v2; ignoring", file=sys.stderr)
            return methods, fields
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if not parts:
                continue
            if len(parts) >= 5 and parts[0] == "" and parts[1] == "m":
                # \tm\t<desc>\t<official>\t<named>
                official = parts[3]
                named = parts[4]
                if official.startswith("func_"):
                    methods[official] = named
            elif len(parts) >= 5 and parts[0] == "" and parts[1] == "f":
                official = parts[3]
                named = parts[4]
                if official.startswith("field_"):
                    fields[official] = named
    return methods, fields


# ----- MITE jar/dir loader ---------------------------------------------------

def load_mite(mite_path: str) -> Dict[str, ClassInfo]:
    """Load all classes under mite_path (a directory of unpacked .class files
    or a .jar) keyed by internal name."""
    classes: Dict[str, ClassInfo] = {}
    if os.path.isdir(mite_path):
        for root, _, files in os.walk(mite_path):
            for fname in files:
                if not fname.endswith(".class"):
                    continue
                full = os.path.join(root, fname)
                try:
                    with open(full, "rb") as f:
                        data = f.read()
                except OSError:
                    continue
                ci = parse_class(data)
                if ci:
                    classes[ci.name] = ci
    elif os.path.isfile(mite_path) and mite_path.endswith(".jar"):
        with zipfile.ZipFile(mite_path) as zf:
            for entry in zf.namelist():
                if not entry.endswith(".class"):
                    continue
                ci = parse_class(zf.read(entry))
                if ci:
                    classes[ci.name] = ci
    else:
        raise SystemExit(f"--mite must be a directory or jar: {mite_path}")
    return classes


# ----- mod jar scanner -------------------------------------------------------

def scan_mod(mod_path: str) -> Tuple[Set[Tuple[str, str, str]], Set[Tuple[str, str, str]], Set[str]]:
    """Return (method_refs, field_refs, mod_class_names) inside a mod jar.

    Refs whose owner class is itself defined in the mod are filtered out — we
    only care about cross-jar calls into vanilla/MITE."""
    method_refs: Set[Tuple[str, str, str]] = set()
    field_refs: Set[Tuple[str, str, str]] = set()
    mod_classes: Set[str] = set()
    with zipfile.ZipFile(mod_path) as zf:
        for entry in zf.namelist():
            if not entry.endswith(".class"):
                continue
            data = zf.read(entry)
            ci = parse_class(data)
            if ci:
                mod_classes.add(ci.name)
            mr, fr = collect_refs(data)
            method_refs.update(mr)
            field_refs.update(fr)
    # filter out self-refs
    method_refs = {r for r in method_refs if r[0] not in mod_classes}
    field_refs = {r for r in field_refs if r[0] not in mod_classes}
    return method_refs, field_refs, mod_classes


# ----- classification --------------------------------------------------------

def classify(
    method_refs: Set[Tuple[str, str, str]],
    field_refs: Set[Tuple[str, str, str]],
    mite: Dict[str, ClassInfo],
    srg_methods: Dict[str, str],
    srg_fields: Dict[str, str],
):
    """Return three lists: green, yellow, red.

    Each entry is (kind, owner, srg_name, desc, mapped_name, note)."""
    green: List[tuple] = []
    yellow: List[tuple] = []
    red: List[tuple] = []

    def lookup_class(owner: str) -> Optional[ClassInfo]:
        # Mod refs may point at non-Minecraft classes (java/lang/Object, sk89q
        # internals, …). We skip those — they're irrelevant to the MITE port.
        if not owner.startswith("net/minecraft/") and not owner.startswith("net/minecraftforge/"):
            return None
        return mite.get(owner)

    for owner, name, desc in sorted(method_refs):
        ci = lookup_class(owner)
        if ci is None:
            # Owner doesn't exist in MITE. Could be a Forge class FishModLoader
            # is supposed to provide as a stub — but that's a different audit.
            if owner.startswith("net/minecraft/"):
                red.append(("M", owner, name, desc, srg_methods.get(name, ""), "owner missing from MITE"))
            continue
        mapped = srg_methods.get(name, name) if name.startswith("func_") else name
        # Walk superclass chain too — a method could be inherited
        same_name_in_chain: List[Tuple[str, str]] = []
        scan = ci
        while scan is not None:
            for m in scan.methods:
                if m.name == mapped:
                    same_name_in_chain.append((scan.name, m.desc))
            scan = mite.get(scan.superclass) if scan.superclass else None
            if scan and scan.name == "java/lang/Object":
                break
        # exact match?
        if any(d == desc for _, d in same_name_in_chain):
            green.append(("M", owner, name, desc, mapped, ""))
        elif same_name_in_chain:
            descs = ", ".join(d for _, d in same_name_in_chain[:3])
            yellow.append(("M", owner, name, desc, mapped, f"name match, MITE descs: {descs}"))
        else:
            red.append(("M", owner, name, desc, mapped, "no name match in MITE"))

    for owner, name, desc in sorted(field_refs):
        ci = lookup_class(owner)
        if ci is None:
            if owner.startswith("net/minecraft/"):
                red.append(("F", owner, name, desc, srg_fields.get(name, ""), "owner missing from MITE"))
            continue
        mapped = srg_fields.get(name, name) if name.startswith("field_") else name
        same_name_in_chain: List[Tuple[str, str]] = []
        scan = ci
        while scan is not None:
            for f in scan.fields:
                if f.name == mapped:
                    same_name_in_chain.append((scan.name, f.desc))
            scan = mite.get(scan.superclass) if scan.superclass else None
            if scan and scan.name == "java/lang/Object":
                break
        if any(d == desc for _, d in same_name_in_chain):
            green.append(("F", owner, name, desc, mapped, ""))
        elif same_name_in_chain:
            descs = ", ".join(d for _, d in same_name_in_chain[:3])
            yellow.append(("F", owner, name, desc, mapped, f"name match, MITE descs: {descs}"))
        else:
            red.append(("F", owner, name, desc, mapped, "no name match in MITE"))

    return green, yellow, red


def write_csv(path: str, rows: List[tuple]) -> None:
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["kind", "owner", "srg_name", "desc", "mapped_name", "note"])
        for r in rows:
            w.writerow(r)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--mod", required=True, help="path to a Forge mod jar")
    p.add_argument("--mite", required=True,
                   help="path to unpacked MITE class directory (or a .jar)")
    p.add_argument("--srg-table", default=None,
                   help="optional tiny v2 mapping with func_*/field_* in the official column")
    p.add_argument("--out", default=None,
                   help="output CSV stem; defaults to mod jar path")
    args = p.parse_args()

    print(f"Scanning {args.mod}", file=sys.stderr)
    method_refs, field_refs, mod_classes = scan_mod(args.mod)
    print(f"  unique method refs: {len(method_refs)}", file=sys.stderr)
    print(f"  unique field refs:  {len(field_refs)}", file=sys.stderr)
    print(f"  classes in mod:     {len(mod_classes)}", file=sys.stderr)

    print(f"Loading MITE classes from {args.mite}", file=sys.stderr)
    mite = load_mite(args.mite)
    print(f"  classes in MITE:    {len(mite)}", file=sys.stderr)

    srg_methods, srg_fields = load_srg_table(args.srg_table) if args.srg_table else ({}, {})
    if args.srg_table:
        print(f"  SRG table:           {len(srg_methods)} methods, {len(srg_fields)} fields", file=sys.stderr)

    green, yellow, red = classify(method_refs, field_refs, mite, srg_methods, srg_fields)

    out_stem = args.out or os.path.splitext(args.mod)[0]
    write_csv(out_stem + ".green.csv", green)
    write_csv(out_stem + ".yellow.csv", yellow)
    write_csv(out_stem + ".red.csv", red)

    total = len(green) + len(yellow) + len(red)
    print()
    print("=== Compatibility report ===")
    print(f"  GREEN  (exact MITE match):       {len(green):4d} / {total}")
    print(f"  YELLOW (name match, diff sig):   {len(yellow):4d} / {total}")
    print(f"  RED    (no MITE match):          {len(red):4d} / {total}")
    print()
    print(f"CSVs: {out_stem}.{{green,yellow,red}}.csv")

    if yellow:
        print()
        print("Top YELLOW (signature drift — usually fixable with a small adapter):")
        for row in yellow[:20]:
            print(f"  [{row[0]}] {row[1]}.{row[4] or row[2]}{row[3]}  →  {row[5]}")
    if red:
        print()
        print("Top RED (will need a stub or has no MITE counterpart):")
        for row in red[:20]:
            print(f"  [{row[0]}] {row[1]}.{row[4] or row[2]}{row[3]}  →  {row[5]}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
