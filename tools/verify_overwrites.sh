#!/usr/bin/env bash
# Verify that every @Overwrite / @Shadow target in the forge_compat mixin
# package actually exists on the corresponding MITE class.
#
# Namespace handling (important):
#   * Mixin sources are written against the *named* namespace (blockID,
#     canBlockStay, ...).
#   * The remapped game jar is in the *intermediary* namespace, where most
#     members keep SRG names (field_71990_ca, func_71854_d, ...).
# So a raw name-vs-jar comparison produces massive false positives. This
# script first translates each named member to its intermediary name using
# src/main/resources/named.tiny, then looks for either form in the jar.
#
# @Overwrite and @Shadow fail hard at mixin-apply time when the target member
# is missing, so any remaining hit is a genuine runtime breakage.
#
# Usage: tools/verify_overwrites.sh [mite-intermediary.jar]

set -uo pipefail

JAR="${1:-build/tmp/mite-intermediary.jar}"
MIXIN_DIR="src/main/java/net/xiaoyu233/fml/reload/transform/forge_compat"
NAMED_TINY="src/main/resources/named.tiny"

for req in "$JAR" "$NAMED_TINY"; do
  if [ ! -f "$req" ]; then
    echo "ERROR: required file not found: $req" >&2
    [ "$req" = "$JAR" ] && echo "Run: ./gradlew remapMiteToIntermediary" >&2
    exit 1
  fi
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
# Extract everything: the jar has no directory entries, so an include
# filter like 'net/minecraft/*' would match nothing.
unzip -oq "$JAR" -d "$WORK" 2>/dev/null || true

# named -> intermediary lookup table (named name may map to several
# intermediary names across classes; keep them all, space separated).
NAMEMAP="$WORK/namemap.txt"
awk -F'\t' '
  $2=="m" || $2=="f" {
    inter=$4; named=$5
    if (named != "" && inter != "") print named "\t" inter
  }
' "$NAMED_TINY" | sort -u > "$NAMEMAP"

declare -A MEMBERS_CACHE
declare -a BAD=()

# All member names declared on a class plus its net.minecraft superclasses.
members_of() {
  local cls="$1"
  if [ -n "${MEMBERS_CACHE[$cls]+x}" ]; then
    printf '%s' "${MEMBERS_CACHE[$cls]}"
    return
  fi

  local out="" cur="$WORK/${cls//.//}.class" guard=0
  while [ -n "$cur" ] && [ -f "$cur" ] && [ "$guard" -lt 24 ]; do
    local dump
    dump="$(javap -p "$cur" 2>/dev/null)" || break
    # Method names: identifier immediately before '('
    out+="$(printf '%s' "$dump" | grep -oE '[A-Za-z_$][A-Za-z0-9_$]*\(' | tr -d '(')"$'\n'
    # Field names: trailing identifier before ';' on lines without '('
    out+="$(printf '%s' "$dump" | grep -vE '\(' \
            | grep -oE '[A-Za-z_$][A-Za-z0-9_$]*;$' | tr -d ';')"$'\n'

    local super
    super="$(printf '%s' "$dump" | grep -oE 'extends [A-Za-z0-9_.$]+' | head -1 | awk '{print $2}')"
    case "${super:-}" in
      net.minecraft.*) cur="$WORK/$(printf '%s' "$super" | tr '.' '/').class" ;;
      *) cur="" ;;
    esac
    guard=$((guard + 1))
  done

  MEMBERS_CACHE[$cls]="$out"
  printf '%s' "$out"
}

total=0; missing=0; skipped=0

for f in "$MIXIN_DIR"/*.java; do
  [ -f "$f" ] || continue
  base="$(basename "$f")"

  target="$(grep -oE '@Mixin\(([A-Za-z0-9_.]+)\.class' "$f" | head -1 \
            | sed -E 's/@Mixin\(//; s/\.class//')"
  [ -z "$target" ] && continue
  simple="${target##*.}"
  fqcn="$(grep -oE "^import (net\.minecraft\.[A-Za-z0-9_.]*\.)${simple};" "$f" \
          | head -1 | sed -E 's/^import //; s/;$//')"
  [ -z "$fqcn" ] && continue

  body="$(members_of "$fqcn")"
  if [ -z "${body//[$'\n' ]/}" ]; then
    echo "SKIP  $base -> $fqcn (class not found in jar)"
    skipped=$((skipped + 1))
    continue
  fi

  names="$(awk '
    function emit(line) {
      if (match(line, /([A-Za-z_$][A-Za-z0-9_$]*)[ \t]*\(/, m)) { print m[1]; return }
      if (match(line, /([A-Za-z_$][A-Za-z0-9_$]*)[ \t]*[;=]/, m)) { print m[1]; return }
    }
    # Only real annotations count: the line must *start* with @Overwrite or
    # @Shadow. This avoids matching those words inside doc comments, and avoids
    # treating @Unique members (intentionally absent from the jar) as errors.
    # NOTE: gawk ERE has no \b, so match the delimiter explicitly.
    /^[ \t]*@(Overwrite|Shadow)([ \t(]|$)/ { pending = 1; next }
    pending {
      s = $0
      gsub(/^[ \t]+|[ \t]+$/, "", s)
      if (s == "") next
      if (s ~ /^\/\// || s ~ /^\/\*/ || s ~ /^\*/) next
      if (s ~ /^@/) next
      emit(s)
      pending = 0
    }
  ' "$f" 2>/dev/null | sort -u)"

  while IFS= read -r name; do
    [ -z "$name" ] && continue
    case "$name" in
      Inject|Mixin|Shadow|Overwrite|Redirect|ModifyArg|ModifyArgs|ModifyVariable|\
      ModifyConstant|ModifyExpressionValue|ModifyReturnValue|ModifyReceiver|\
      WrapOperation|WrapWithCondition|Unique|Final|Accessor|Invoker|SideOnly|\
      if|for|while|switch|return|new|this|super|throw|catch|else|do|try|\
      public|private|protected|static|final|abstract|native|synchronized|\
      class|interface|enum|void|signature|flags|active|descriptor) continue ;;
    esac
    total=$((total + 1))

    # Present directly (already SRG//unmapped, or MITE-added readable name)?
    if printf '%s' "$body" | grep -qxF "$name"; then
      continue
    fi
    # Otherwise translate named -> intermediary and retry.
    hit=0
    while IFS= read -r inter; do
      [ -z "$inter" ] && continue
      if printf '%s' "$body" | grep -qxF "$inter"; then hit=1; break; fi
    done < <(awk -F'\t' -v n="$name" '$1==n {print $2}' "$NAMEMAP" | sort -u)

    if [ "$hit" -eq 0 ]; then
      missing=$((missing + 1))
      BAD+=("${base%Mixin.java} :: $name")
    fi
  done <<< "$names"
done

echo
echo "=================================================="
echo "Checked @Overwrite/@Shadow targets : $total"
echo "Missing on MITE classes            : $missing"
echo "Classes skipped (not in jar)       : $skipped"
echo "=================================================="
if [ "${#BAD[@]}" -gt 0 ]; then
  printf '%s\n' "${BAD[@]}" | sort -u
  exit 1
fi
echo "All targets resolved."
