#!/usr/bin/env python3
"""
Catch Modifier extensions used without their import.

`Modifier.clickable { }` is an extension function, so a missing import shows up as
`unresolved reference 'clickable'` on a *member access* - and check_unresolved.py
deliberately drops member accesses, because without the Android SDK an unresolved
member almost always means an unresolved receiver. This class of mistake therefore
slips through that filter entirely, and it has bitten this project three times
(weight, graphicsLayer, clickable).

The expected import for each extension is learned from the codebase rather than
hardcoded: if twenty files import androidx.compose.foundation.clickable, that is
what `.clickable` on a Modifier chain needs. Only chains rooted at `Modifier` are
inspected, so `list.size` and `bitmap.width` cannot be mistaken for modifiers.

Usage: python3 tools/check_modifier_imports.py
"""
import re, glob, sys, os, collections

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                    "app/src/main/java")

def main():
    files = glob.glob(os.path.join(ROOT, "**/*.kt"), recursive=True)
    sources = {f: open(f).read() for f in files}

    # Learn name -> import path from every import the project already makes.
    known = collections.defaultdict(collections.Counter)
    for src in sources.values():
        for path in re.findall(r'^import\s+([\w.]+)$', src, re.M):
            known[path.rsplit('.', 1)[-1]][path] += 1

    problems = []
    for f, src in sources.items():
        imported = {p.rsplit('.', 1)[-1] for p in re.findall(r'^import\s+([\w.]+)', src, re.M)}
        declared = set(re.findall(r'\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?(\w+)\s*\(', src))

        # A Modifier chain: the word Modifier, then .name links, across lines.
        for m in re.finditer(r'\bModifier\b((?:\s*\.\s*\w+\s*(?:\([^()]*\))?\s*(?:\{)?)+)', src):
            for name in re.findall(r'\.\s*(\w+)', m.group(1)):
                if name in imported or name in declared:
                    continue
                if name not in known:
                    continue                       # never imported anywhere: not ours to judge
                best = known[name].most_common(1)[0][0]
                if not best.startswith("androidx.compose"):
                    continue
                line = src[:m.start()].count('\n') + 1
                problems.append((os.path.relpath(f, ROOT), line, name, best))

    if not problems:
        print("every Modifier extension used is imported")
        return 0
    print(f"MISSING MODIFIER IMPORTS ({len(problems)}):")
    for f, line, name, imp in sorted(set(problems)):
        print(f"  {f}:{line}  .{name}  -> needs `import {imp}`")
    return 1

if __name__ == "__main__":
    sys.exit(main())
