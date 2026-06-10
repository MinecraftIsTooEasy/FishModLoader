#!/usr/bin/env python3
"""
Generate a manual-review CSV for SRG names that the auto-guesser couldn't
resolve. Each row tells the reviewer everything they need to make a one-shot
decision — no JAR diving required.

For every SRG name that was skipped:
  1. Owner class + descriptor.
  2. Every WE class that calls it (so you can grep where it's used).
  3. Every same-descriptor method candidate in MITE's owner class chain
     (these are the deobf names a human has to choose between, or reject).
  4. A "common-forge-name" hint when I happen to know what this SRG meant
     in vanilla 1.6.4. Treat hints as suggestions only — verify the
     candidates above before trusting them.

Usage:
    python3 build_manual_srg_review.py \\
        --mod   /path/to/worldedit.jar \\
        --mite  /tmp/mite-jar-extract \\
        --out   /tmp/we-srg-review.csv
"""

from __future__ import annotations

import argparse
import csv
import os
import sys
import zipfile
from collections import defaultdict
from typing import Dict, List, Set, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from scan_forge_compat import ClassInfo, parse_class, collect_refs, load_mite  # type: ignore
from guess_srg_mappings import (  # type: ignore
    gather_srg_refs,
    guess_methods,
    guess_fields,
    methods_in_class_only,
    fields_in_class_only,
)


# ---------------------------------------------------------------------------
# Hand-curated 1.6.4 SRG → vanilla deobf hints. These come from MCP 1.6.4
# (mcp_stable-22) memory; do NOT trust without verifying against the MITE
# candidate list also shown in the row.
#
# Only widely-known names are included. When MITE has substantively changed
# the method, the hint will mismatch the candidate list — that's the signal
# to stop and do something custom.
# ---------------------------------------------------------------------------

KNOWN_METHOD_NAMES = {
    "func_70006_a": "addChatMessage",
    "func_70012_b": "setLocationAndAngles",
    "func_70020_e": "copyDataFrom",
    "func_70106_y": "setDead",
    "func_70109_d": "writeToNBT",
    "func_70299_a": "setInventorySlotContents",
    "func_70302_i_": "getSizeInventory",
    "func_70307_a": "readFromNBT",
    "func_70310_b": "writeToNBT",
    "func_70317_c": "createAndLoadEntity",
    "func_70441_a": "addItemStackToInventory",
    "func_70909_n": "isTamed",
    "func_71045_bC": "getCurrentEquippedItem",
    "func_71187_D": "getCommandManager",
    "func_71203_ab": "getConfigurationManager",
    "func_71514_a": "getCommandAliases",
    "func_71515_b": "processCommand",
    "func_71517_b": "getCommandName",
    "func_71518_a": "getCommandUsage",
    "func_71525_a": "compareTo",
    "func_71560_a": "registerCommand",
    "func_72353_e": "areCommandsAllowed",
    "func_72361_f": "getPlayerForUsername",
    "func_72369_d": "getAllUsernames",
    "func_72567_b": "sendPacketToAllPlayersTrackingEntity",
    "func_72569_a": "setPlayerLocation",
    "func_72796_p": "getBlockTileEntity",
    "func_72798_a": "getBlockId",
    "func_72805_g": "getBlockMetadata",
    "func_72807_a": "getBiomeGenForCoords",
    "func_72837_a": "addEntity",
    "func_72838_d": "spawnEntityInWorld",
    "func_72845_h": "isAirBlock",
    "func_72851_f": "checkChunksExist",
    "func_72863_F": "getChunkProvider",
    "func_72910_y": "getLoadedEntityList",
    "func_72912_H": "getWorldInfo",
    "func_72938_d": "getChunkFromBlockCoords",
    "func_72957_l": "getGameRules",
    "func_72964_e": "getChunkFromChunkCoords",
    "func_72969_x": "markBlockForRenderUpdate",
    "func_73149_a": "chunkExists",
    "func_73154_d": "provideChunk",
    "func_73158_c": "loadChunk",
    "func_74740_e": "getCompoundTag",
    "func_74742_a": "appendTag",
    "func_74743_b": "removeTag",
    "func_74745_c": "tagAt",
    "func_74758_c": "getTags",
    "func_74778_a": "setString",
    "func_74782_a": "setTag",
    "func_75620_a": "createEntityByName",
    "func_75621_b": "getEntityString",
    "func_76065_j": "getWorldName",
    "func_76159_d": "getTagOfType",
    "func_76163_a": "add",
    "func_76592_a": "getPath",
    "func_76605_m": "getBiomeArray",
    "func_76610_a": "setBiomeArray",
    "func_76623_d": "removeChunk",
    "func_76624_a": "populateChunk",
    "func_76631_c": "saveChunk",
    "func_77272_a": "chunkXZ2Int",
    "func_77658_a": "getUnlocalizedName",
    "func_77966_a": "addEnchantment",
    "func_82580_o": "removeTag",
    "func_94056_bM": "hasCustomNameTag",
    "func_96440_m": "getEnableCommandBlocks",
    "func_96468_q_": "isReplaceable",
    "func_110124_au": "getUniqueID",
    "func_111066_d": "createFromText",
}

KNOWN_FIELD_NAMES = {
    # add as you collect more from the report
}


def gather_call_sites(mod_path: str) -> Dict[Tuple[str, str, str], List[str]]:
    """For each (owner, name, desc) ref, list the WE classes that call it."""
    sites: Dict[Tuple[str, str, str], List[str]] = defaultdict(list)
    with zipfile.ZipFile(mod_path) as zf:
        for entry in zf.namelist():
            if not entry.endswith(".class"):
                continue
            data = zf.read(entry)
            ci = parse_class(data)
            mr, fr = collect_refs(data)
            caller = ci.name if ci else entry
            for ref in mr | fr:
                sites[ref].append(caller)
    return sites


def candidates_in_chain(
    mite: Dict[str, ClassInfo],
    owner: str,
    desc: str,
    is_method: bool,
) -> List[Tuple[str, str]]:
    """Walk the super chain and return [(declaring_class, name), …] for every
    declaration that matches the descriptor and isn't itself a SRG name."""
    out: List[Tuple[str, str]] = []
    seen: Set[str] = set()
    ci = mite.get(owner)
    while ci and ci.name not in seen:
        seen.add(ci.name)
        if is_method:
            for m in ci.methods:
                if m.desc == desc and not m.name.startswith("func_") and not m.name.startswith("<"):
                    out.append((ci.name, m.name))
        else:
            for f in ci.fields:
                if f.desc == desc and not f.name.startswith("field_"):
                    out.append((ci.name, f.name))
        ci = mite.get(ci.superclass) if ci.superclass else None
    return out


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--mod", required=True)
    p.add_argument("--mite", required=True)
    p.add_argument("--out", required=True)
    args = p.parse_args()

    mr_owners, fr_owners = gather_srg_refs(args.mod)
    print(f"SRG methods: {len(mr_owners)}", file=sys.stderr)
    print(f"SRG fields:  {len(fr_owners)}", file=sys.stderr)

    mite = load_mite(args.mite)
    sites = gather_call_sites(args.mod)

    # Re-run guesser to identify what's already covered, so we only review
    # the unguessed remainder.
    auto_methods, _ = guess_methods(mr_owners, mite)
    auto_fields, _ = guess_fields(fr_owners, mite)
    auto_method_keys = set(auto_methods.keys())  # (srg, desc)
    auto_field_keys = set(auto_fields.keys())

    rows: List[List[str]] = []

    for srg, occurrences in sorted(mr_owners.items()):
        for owner, desc in sorted(occurrences):
            key = (srg, desc)
            if key in auto_method_keys:
                continue
            cands = candidates_in_chain(mite, owner, desc, is_method=True)
            cand_str = " | ".join(f"{c}::{n}" for c, n in cands) if cands else "(none in MITE chain)"
            callers = sorted(set(sites.get((owner, srg, desc), []))) or sorted(
                set(sites.get((owner, srg, desc), []))
            )
            caller_str = ", ".join(callers[:5]) + (" …" if len(callers) > 5 else "")
            hint = KNOWN_METHOD_NAMES.get(srg, "")
            rows.append([
                "method",
                srg,
                owner,
                desc,
                hint,
                cand_str,
                caller_str,
            ])

    for srg, occurrences in sorted(fr_owners.items()):
        for owner, desc in sorted(occurrences):
            key = (srg, desc)
            if key in auto_field_keys:
                continue
            cands = candidates_in_chain(mite, owner, desc, is_method=False)
            cand_str = " | ".join(f"{c}::{n}" for c, n in cands) if cands else "(none in MITE chain)"
            callers = sorted(set(sites.get((owner, srg, desc), [])))
            caller_str = ", ".join(callers[:5]) + (" …" if len(callers) > 5 else "")
            hint = KNOWN_FIELD_NAMES.get(srg, "")
            rows.append([
                "field",
                srg,
                owner,
                desc,
                hint,
                cand_str,
                caller_str,
            ])

    with open(args.out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow([
            "kind",
            "srg_name",
            "owner",
            "descriptor",
            "hint_vanilla_name",
            "MITE_candidates_same_desc",
            "called_by",
        ])
        for r in rows:
            w.writerow(r)

    print()
    print(f"Wrote {len(rows)} rows to {args.out}")
    print()
    print("Quick preview of items where the vanilla hint matches a MITE candidate")
    print("(these are the lowest-risk to add to forge-srg.tiny):")
    matched_quick = 0
    for r in rows:
        kind, srg, owner, desc, hint, cands, callers = r
        if not hint:
            continue
        cand_names = [c.split("::", 1)[1] for c in cands.split(" | ") if "::" in c]
        if hint in cand_names:
            matched_quick += 1
            print(f"  ✓ {srg} → {hint}  (on {owner})")
    print()
    print(f"{matched_quick} of {len(rows)} have a hint that matches a MITE candidate.")
    print("Items where hint exists but does NOT match (== MITE definitely changed it):")
    for r in rows:
        kind, srg, owner, desc, hint, cands, callers = r
        if not hint:
            continue
        cand_names = [c.split("::", 1)[1] for c in cands.split(" | ") if "::" in c]
        if hint not in cand_names:
            print(f"  ✗ {srg} → vanilla='{hint}' but MITE has [{cands}]  on {owner}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
