#!/usr/bin/env python3
"""
Combine the auto-guessed SRG → MITE deobf mappings with the safe subset of
the manual review hints (those whose vanilla MCP name appears verbatim in
the MITE class chain) to produce the final forge-srg-1.6.4.tiny file
shipped with FishModLoader.

The unsafe hints (where the vanilla name is NOT in the MITE chain — i.e.
MITE has changed the method/field) are deliberately *not* mapped here.
Letting them surface as NoSuchMethodError at runtime is safer than a wrong
mapping that silently runs the wrong code.

Usage:
    python3 build_final_srg_tiny.py \\
        --mod   /path/to/worldedit.jar \\
        --mite  /tmp/mite-jar-extract \\
        --out   src/main/resources/forge-srg-1.6.4.tiny
"""

from __future__ import annotations

import argparse
import os
import sys
from typing import Dict, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from scan_forge_compat import load_mite, ClassInfo  # type: ignore
from guess_srg_mappings import (  # type: ignore
    gather_srg_refs,
    guess_methods,
    guess_fields,
    methods_in_class_only,
    fields_in_class_only,
)
from build_manual_srg_review import (  # type: ignore
    KNOWN_METHOD_NAMES,
    KNOWN_FIELD_NAMES,
    candidates_in_chain,
)


def hint_matches_chain(
    mite: Dict[str, ClassInfo],
    owner: str,
    desc: str,
    hint: str,
    is_method: bool,
) -> bool:
    """Return True iff `hint` is a (name, desc) pair that exists somewhere
    in MITE's class chain rooted at owner."""
    cands = candidates_in_chain(mite, owner, desc, is_method=is_method)
    return any(name == hint for _, name in cands)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--mod", required=True)
    p.add_argument("--mite", required=True)
    p.add_argument("--out", required=True)
    args = p.parse_args()

    mr_owners, fr_owners = gather_srg_refs(args.mod)
    mite = load_mite(args.mite)

    # Stage 1: high-confidence auto guesses (descriptor uniquely identifies
    # one non-srg method in MITE's chain).
    auto_methods, _ = guess_methods(mr_owners, mite)
    auto_fields, _ = guess_fields(fr_owners, mite)

    # Stage 2: hint-matched entries. For each SRG name we have a vanilla
    # hint for, accept it only if a method/field of that exact name and
    # descriptor exists in MITE's chain rooted at the actual call-site
    # owner.
    hint_methods: Dict[Tuple[str, str], str] = {}
    hint_fields: Dict[Tuple[str, str], str] = {}

    for srg, occurrences in mr_owners.items():
        hint = KNOWN_METHOD_NAMES.get(srg)
        if not hint:
            continue
        for owner, desc in occurrences:
            if (srg, desc) in auto_methods:
                continue  # already covered
            if hint_matches_chain(mite, owner, desc, hint, is_method=True):
                hint_methods[(srg, desc)] = hint

    for srg, occurrences in fr_owners.items():
        hint = KNOWN_FIELD_NAMES.get(srg)
        if not hint:
            continue
        for owner, desc in occurrences:
            if (srg, desc) in auto_fields:
                continue
            if hint_matches_chain(mite, owner, desc, hint, is_method=False):
                hint_fields[(srg, desc)] = hint

    # Merge.
    final_methods: Dict[Tuple[str, str], str] = dict(auto_methods)
    final_methods.update(hint_methods)
    final_fields: Dict[Tuple[str, str], str] = dict(auto_fields)
    final_fields.update(hint_fields)

    # Sanity-check: any conflicts between auto and hint stages?
    conflicts = 0
    for k in auto_methods.keys() & hint_methods.keys():
        if auto_methods[k] != hint_methods[k]:
            print(f"  CONFLICT m: {k} auto={auto_methods[k]} hint={hint_methods[k]}", file=sys.stderr)
            conflicts += 1
    for k in auto_fields.keys() & hint_fields.keys():
        if auto_fields[k] != hint_fields[k]:
            print(f"  CONFLICT f: {k} auto={auto_fields[k]} hint={hint_fields[k]}", file=sys.stderr)
            conflicts += 1
    if conflicts:
        print(f"WARNING: {conflicts} conflicts. Inspect output before shipping.", file=sys.stderr)

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        # Tiny v2 readers don't accept comment lines, so we keep this file
        # strictly mappings-only. Provenance lives in the matching script
        # at tools/build_final_srg_tiny.py.
        f.write("tiny\t2\t0\tofficial\tnamed\n")
        for (srg, desc), name in sorted(final_methods.items()):
            f.write(f"\tm\t{desc}\t{srg}\t{name}\n")
        for (srg, desc), name in sorted(final_fields.items()):
            f.write(f"\tf\t{desc}\t{srg}\t{name}\n")

    print(f"Wrote {args.out}")
    print(f"  Total methods: {len(final_methods)}  (auto={len(auto_methods)}, hint={len(hint_methods)})")
    print(f"  Total fields:  {len(final_fields)}   (auto={len(auto_fields)}, hint={len(hint_fields)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
