#!/usr/bin/env python3
"""
Augment scan_forge_compat.py with a "signature-based reverse lookup" pass:
when a SRG name has no entry in the supplied table, walk the MITE class
hierarchy and *guess* the deobf name by matching descriptors in the
declaring class. We emit a tiny v2 file with the guesses so they can be
audited and fed back into the real mapping table.

This is a heuristic — multiple deobf methods may share a descriptor — so
we only emit a guess when:
  - the SRG name is referenced in only one descriptor across all of the
    mod's call sites; AND
  - the candidate MITE class declares exactly one method with that descriptor
    that doesn't appear in the parent class.

Whatever we can't guess we just leave un-mapped; humans can fill in the
ambiguous ones from the .red CSV.

Usage:
    python3 guess_srg_mappings.py \\
        --mod   worldedit.jar \\
        --mite  /tmp/mite-jar-extract \\
        --out   forge-srg-guessed.tiny
"""

from __future__ import annotations

import argparse
import os
import sys
import zipfile
from collections import defaultdict
from typing import Dict, List, Set, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from scan_forge_compat import (  # type: ignore
    ClassInfo,
    parse_class,
    collect_refs,
    load_mite,
)


def gather_srg_refs(mod_path: str):
    method_owners_by_name: Dict[str, Set[Tuple[str, str]]] = defaultdict(set)  # srg → {(owner, desc)}
    field_owners_by_name: Dict[str, Set[Tuple[str, str]]] = defaultdict(set)
    with zipfile.ZipFile(mod_path) as zf:
        for entry in zf.namelist():
            if not entry.endswith(".class"):
                continue
            data = zf.read(entry)
            mr, fr = collect_refs(data)
            for owner, name, desc in mr:
                if name.startswith("func_"):
                    method_owners_by_name[name].add((owner, desc))
            for owner, name, desc in fr:
                if name.startswith("field_"):
                    field_owners_by_name[name].add((owner, desc))
    return method_owners_by_name, field_owners_by_name


def methods_in_class_only(ci: ClassInfo) -> Dict[str, List[str]]:
    by_desc: Dict[str, List[str]] = defaultdict(list)
    for m in ci.methods:
        by_desc[m.desc].append(m.name)
    return by_desc


def fields_in_class_only(ci: ClassInfo) -> Dict[str, List[str]]:
    by_desc: Dict[str, List[str]] = defaultdict(list)
    for f in ci.fields:
        by_desc[f.desc].append(f.name)
    return by_desc


def guess_methods(
    method_owners: Dict[str, Set[Tuple[str, str]]],
    mite: Dict[str, ClassInfo],
):
    out: Dict[Tuple[str, str], str] = {}  # (srg, desc) → guessed name
    skipped: List[Tuple[str, str, str, str]] = []  # (srg, owner, desc, reason)

    for srg, occurrences in method_owners.items():
        # We only handle calls where the descriptor is the same across all
        # call sites — otherwise the SRG name doesn't uniquely identify a
        # method and a single mapping line wouldn't be valid anyway.
        descs = {desc for _, desc in occurrences}
        if len(descs) != 1:
            for owner, desc in occurrences:
                skipped.append((srg, owner, desc, f"ambiguous: {len(descs)} descriptors"))
            continue
        (desc,) = descs

        # Try each owner the call site mentioned. If any owner (or any class
        # in its super chain) has exactly one same-desc method that doesn't
        # appear in its parent, take that as the guess.
        guessed_name: str | None = None
        guess_source: str = ""
        ambiguous = False
        for owner, _ in occurrences:
            if not owner.startswith("net/minecraft"):
                continue
            ci = mite.get(owner)
            visited: Set[str] = set()
            while ci and ci.name not in visited:
                visited.add(ci.name)
                here = methods_in_class_only(ci)
                cands = here.get(desc, [])
                # filter out known-srg names and synthetic/init
                cands = [c for c in cands if not c.startswith("func_") and not c.startswith("<")]
                # drop names inherited from super (they'd duplicate)
                if ci.superclass and ci.superclass in mite:
                    super_here = methods_in_class_only(mite[ci.superclass])
                    super_cands = set(super_here.get(desc, []))
                    cands = [c for c in cands if c not in super_cands]
                if len(cands) == 1:
                    if guessed_name is None:
                        guessed_name = cands[0]
                        guess_source = ci.name
                    elif guessed_name != cands[0]:
                        ambiguous = True
                        break
                elif len(cands) > 1:
                    ambiguous = True
                    break
                ci = mite.get(ci.superclass) if ci.superclass else None
            if ambiguous:
                break
        if guessed_name and not ambiguous:
            out[(srg, desc)] = guessed_name
        else:
            for owner, _ in occurrences:
                skipped.append((srg, owner, desc, "ambiguous or no candidate"))

    return out, skipped


def guess_fields(
    field_owners: Dict[str, Set[Tuple[str, str]]],
    mite: Dict[str, ClassInfo],
):
    out: Dict[Tuple[str, str], str] = {}
    skipped: List[Tuple[str, str, str, str]] = []

    for srg, occurrences in field_owners.items():
        descs = {desc for _, desc in occurrences}
        if len(descs) != 1:
            for owner, desc in occurrences:
                skipped.append((srg, owner, desc, f"ambiguous: {len(descs)} descriptors"))
            continue
        (desc,) = descs

        guessed_name: str | None = None
        ambiguous = False
        for owner, _ in occurrences:
            if not owner.startswith("net/minecraft"):
                continue
            ci = mite.get(owner)
            visited: Set[str] = set()
            while ci and ci.name not in visited:
                visited.add(ci.name)
                here = fields_in_class_only(ci)
                cands = here.get(desc, [])
                cands = [c for c in cands if not c.startswith("field_")]
                if ci.superclass and ci.superclass in mite:
                    super_cands = set(fields_in_class_only(mite[ci.superclass]).get(desc, []))
                    cands = [c for c in cands if c not in super_cands]
                if len(cands) == 1:
                    if guessed_name is None:
                        guessed_name = cands[0]
                    elif guessed_name != cands[0]:
                        ambiguous = True
                        break
                elif len(cands) > 1:
                    ambiguous = True
                    break
                ci = mite.get(ci.superclass) if ci.superclass else None
            if ambiguous:
                break
        if guessed_name and not ambiguous:
            out[(srg, desc)] = guessed_name
        else:
            for owner, _ in occurrences:
                skipped.append((srg, owner, desc, "ambiguous or no candidate"))

    return out, skipped


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--mod", required=True)
    p.add_argument("--mite", required=True)
    p.add_argument("--out", required=True)
    args = p.parse_args()

    mr_owners, fr_owners = gather_srg_refs(args.mod)
    print(f"SRG methods in mod: {len(mr_owners)}", file=sys.stderr)
    print(f"SRG fields in mod:  {len(fr_owners)}", file=sys.stderr)

    mite = load_mite(args.mite)
    print(f"MITE classes:       {len(mite)}", file=sys.stderr)

    mguess, mskip = guess_methods(mr_owners, mite)
    fguess, fskip = guess_fields(fr_owners, mite)
    print(f"Guessed methods:    {len(mguess)}", file=sys.stderr)
    print(f"Guessed fields:     {len(fguess)}", file=sys.stderr)
    print(f"Skipped methods:    {len(mskip)}", file=sys.stderr)
    print(f"Skipped fields:     {len(fskip)}", file=sys.stderr)

    with open(args.out, "w", encoding="utf-8") as f:
        f.write("tiny\t2\t0\tofficial\tnamed\n")
        for (srg, desc), named in sorted(mguess.items()):
            f.write(f"\tm\t{desc}\t{srg}\t{named}\n")
        for (srg, desc), named in sorted(fguess.items()):
            f.write(f"\tf\t{desc}\t{srg}\t{named}\n")

    print()
    print(f"Wrote {args.out}")
    print()
    print("Sample mappings (eyeball these for sanity):")
    for (srg, desc), named in list(mguess.items())[:25]:
        print(f"  {srg}{desc}  →  {named}")
    print()
    print(f"Skipped (need manual): {len(mskip) + len(fskip)} entries — see stderr above for counts")
    if mskip:
        print()
        print("First few unguessed methods:")
        for s in mskip[:15]:
            print(f"  {s[0]}{s[2]} on {s[1]} — {s[3]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
