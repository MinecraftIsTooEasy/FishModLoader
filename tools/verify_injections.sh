#!/usr/bin/env bash
# Detects mixin defects that verify_overwrites.sh cannot see.
#
# verify_overwrites.sh only asks "does a member with this NAME exist on the MITE
# class?". That leaves three failure modes wide open, all of which shipped
# undetected in ItemStackMixin (arriving with f78f589):
#
#   1. SELF-RECURSION -- an @Inject at HEAD whose body calls the very same
#      @Shadow method it is injecting into. Guaranteed StackOverflowError the
#      first time the method runs, yet every shadowed name resolves fine.
#
#   2. RETURN-TYPE / DESCRIPTOR MISMATCH -- @Shadow declares
#      `void setItemDamage(int)` while MITE declares
#      `ItemStack setItemDamage(int)`. The name matches, so the name-only check
#      passes; mixin apply then fails or silently drops the member.
#
#   3. MISSING INJECT TARGET -- @Inject(method = "...") naming a method that
#      does not exist. verify_overwrites.sh deliberately only looks at
#      @Shadow/@Overwrite, so @Inject targets are never validated at all.
#
# Usage: bash tools/verify_injections.sh [gameJar]
#   gameJar defaults to build/tmp/mite-named.jar (named namespace, matches
#   mixin source names).

set -uo pipefail
cd "$(dirname "$0")/.."

JAR="${1:-build/tmp/mite-named.jar}"
SRC="src/main/java/net/xiaoyu233/fml/reload/transform"

if [ ! -f "$JAR" ]; then
  echo "Game jar not found: $JAR" >&2
  echo "Run 'bash tools/build-compile.sh compileJava' first." >&2
  exit 1
fi
if ! command -v javap >/dev/null 2>&1; then
  echo "javap not found on PATH" >&2
  exit 1
fi

python3 - "$JAR" "$SRC" <<'PY'
import os, re, subprocess, sys, zipfile

jar, src_root = sys.argv[1], sys.argv[2]

# ---- index the jar so we can resolve @Mixin targets to real classes ----------
with zipfile.ZipFile(jar) as zf:
    classes = {n[:-6].replace('/', '.') for n in zf.namelist() if n.endswith('.class')}

simple_to_fqcn = {}
for fq in classes:
    simple_to_fqcn.setdefault(fq.rsplit('.', 1)[-1], []).append(fq)

_javap_cache = {}
def javap(fqcn, with_code=False):
    """Return javap output for a class, or None when absent from the jar."""
    key = (fqcn, with_code)
    if key in _javap_cache:
        return _javap_cache[key]
    if fqcn not in classes:
        _javap_cache[key] = None
        return None
    cmd = ['javap', '-p'] + (['-c'] if with_code else []) + ['-classpath', jar, fqcn]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    _javap_cache[key] = out
    return out

def superclass(fqcn):
    out = javap(fqcn)
    if not out:
        return None
    m = re.search(r'\b(?:class|interface)\s+' + re.escape(fqcn) + r'\b[^{]*?\bextends\s+([\w.$]+)', out)
    return m.group(1) if m else None

def chain(fqcn, limit=24):
    """fqcn plus its superclasses, so 'declared only' javap output is usable."""
    seen, cur = [], fqcn
    while cur and cur not in seen and len(seen) < limit:
        seen.append(cur)
        if cur == 'java.lang.Object':
            break
        cur = superclass(cur)
    return seen

def methods_of(fqcn):
    """{name: [full declaration line]} across the whole superclass chain."""
    found = {}
    for cls in chain(fqcn):
        out = javap(cls)
        if not out:
            continue
        for line in out.splitlines():
            line = line.strip().rstrip(';')
            if line.startswith('Compiled'):
                continue
            # Drop a trailing throws clause: javap prints
            #   public void readPacketData(java.io.DataInput) throws java.io.IOException
            # and matching on ")\s*$" would miss every such method.
            line = re.sub(r'\s+throws\s+[\w.$,\s]+$', '', line)
            m = re.match(r'.*?\b(\w+)\s*\(([^)]*)\)\s*$', line)
            if m:
                found.setdefault(m.group(1), []).append((cls, line))
    return found

# ---- parse the mixin sources -------------------------------------------------
def brace_block(text, start):
    """Text of the {...} block beginning at or after `start`."""
    b = text.find('{', start)
    if b < 0:
        return ''
    depth = 0
    for i in range(b, len(text)):
        if text[i] == '{':
            depth += 1
        elif text[i] == '}':
            depth -= 1
            if depth == 0:
                return text[b:i]
    return text[b:]

RECURSION, MISMATCH, NOTARGET = [], [], []

for dirpath, _, files in os.walk(src_root):
    for fn in files:
        if not fn.endswith('.java'):
            continue
        path = os.path.join(dirpath, fn)
        text = open(path, encoding='utf-8', errors='replace').read()

        m = re.search(r'@Mixin\s*\(\s*(?:value\s*=\s*)?\{?\s*([\w.$]+)\s*\.class', text)
        if not m:
            continue
        target_simple = m.group(1).rsplit('.', 1)[-1]
        cands = simple_to_fqcn.get(target_simple, [])
        if len(cands) != 1:
            continue          # ambiguous or absent; verify_overwrites covers this
        target = cands[0]
        avail = methods_of(target)

        def lineno(idx):
            return text.count('\n', 0, idx) + 1

        # shadowed method names declared in this mixin
        shadow_methods = {}
        for sm in re.finditer(r'@Shadow\b[^;{]*?(?:\babstract\b)?[^;{]*?\b([\w.$<>\[\]]+)\s+(\w+)\s*\(([^)]*)\)\s*[;{]', text):
            ret, name, args = sm.group(1), sm.group(2), sm.group(3)
            if name in ('if', 'for', 'while', 'switch', 'return', 'new'):
                continue
            shadow_methods[name] = (ret, args, lineno(sm.start()))

            # (2) return-type mismatch against the jar
            decls = avail.get(name, [])
            if decls:
                def norm(t):
                    return t.rsplit('.', 1)[-1].strip()
                jar_rets = set()
                for cls, decl in decls:
                    d = re.match(r'(?:(?:public|private|protected|static|final|abstract|native|synchronized|transient|volatile)\s+)*(.*?)\s*\b'
                                 + re.escape(name) + r'\s*\(', decl)
                    if d:
                        jar_rets.add(norm(d.group(1)))
                if jar_rets and norm(ret) not in jar_rets:
                    MISMATCH.append((path, shadow_methods[name][2], name, norm(ret), sorted(jar_rets)))

        # (1) self-recursive injections and (3) missing @Inject targets
        for im in re.finditer(r'@(Inject|Redirect|ModifyVariable|ModifyArg|ModifyArgs|ModifyConstant)\s*\(', text):
            ann = brace_block_start = im.end()
            # annotation argument list
            depth, j = 1, im.end()
            while j < len(text) and depth:
                if text[j] == '(':
                    depth += 1
                elif text[j] == ')':
                    depth -= 1
                j += 1
            annotation = text[im.end():j]
            tm = re.search(r'method\s*=\s*"([^"(]+)', annotation)
            if not tm:
                continue
            tname = tm.group(1).strip()
            ln = lineno(im.start())

            if tname not in avail and not tname.startswith('<'):
                NOTARGET.append((path, ln, tname, target))
                continue

            body = brace_block(text, j)
            if tname in shadow_methods and re.search(r'\bthis\s*\.\s*' + re.escape(tname) + r'\s*\(', body):
                RECURSION.append((path, ln, tname))

def show(title, rows, fmt):
    print()
    print('=' * 66)
    print(f'{title}: {len(rows)}')
    print('=' * 66)
    for r in rows:
        print('  ' + fmt(r))

show('Self-recursive injections (guaranteed StackOverflowError)', RECURSION,
     lambda r: f'{os.path.relpath(r[0])}:{r[1]}  @Inject into "{r[2]}" calls this.{r[2]}()')

show('@Shadow return type disagrees with the jar', MISMATCH,
     lambda r: f'{os.path.relpath(r[0])}:{r[1]}  {r[2]}: mixin says "{r[3]}", jar says {r[4]}')

show('Injection target missing on the MITE class', NOTARGET,
     lambda r: f'{os.path.relpath(r[0])}:{r[1]}  "{r[2]}" not found on {r[3]} (incl. superclasses)')

total = len(RECURSION) + len(MISMATCH) + len(NOTARGET)
print()
if total:
    print(f'DEFECTS: {total}')
else:
    print('No injection defects found.')
sys.exit(0)
PY
