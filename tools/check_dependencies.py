#!/usr/bin/env python3
"""Check that every library the source imports is one the module actually declares.

This exists because of a failure that read as four unrelated compiler errors and
was none of them. The app imported `androidx.compose.foundation` in four hundred
places and `androidx.compose.runtime` in a hundred, and declared neither - both
arrived behind material3. A transitive dependency is a version somebody else
chose, and when the Compose runtime it landed on was out of step with the Compose
compiler, the error said "Cannot access class ComposableFunction1", and then
every `Row { }` in the file lost its scope and `Modifier.weight` went unresolved.
Nothing pointed at the build file.

The rule is the plain one: if you import it, declare it. The table below maps an
import prefix to the Gradle alias that provides it; a prefix with no entry is
reported too, so the table cannot quietly fall behind the source.

Run with no arguments from the repo root.
"""

import pathlib
import re
import sys

SOURCE = pathlib.Path("app/src/main/java")
MODULE_BUILD = pathlib.Path("app/build.gradle.kts")

# Import prefix -> the libs.versions.toml alias that must be declared for it.
# Longest prefix wins, so a more specific artifact can override a broader one.
PROVIDED_BY = {
    "androidx.activity": "androidx.activity.compose",
    "androidx.compose.animation": "androidx.compose.foundation",
    "androidx.compose.foundation.layout": "androidx.compose.foundation.layout",
    "androidx.compose.foundation": "androidx.compose.foundation",
    "androidx.compose.material.icons": "androidx.material.icons.extended",
    "androidx.compose.material3": "androidx.material3",
    "androidx.compose.runtime": "androidx.compose.runtime",
    "androidx.compose.ui.graphics": "androidx.ui.graphics",
    "androidx.compose.ui.tooling": "androidx.ui.tooling.preview",
    "androidx.compose.ui": "androidx.ui",
    "androidx.core.splashscreen": "androidx.core.splashscreen",
    "androidx.core": "androidx.core.ktx",
    "androidx.lifecycle.viewmodel": "androidx.lifecycle.viewmodel.compose",
    "androidx.lifecycle": "androidx.lifecycle.runtime.ktx",
    "androidx.media3.common": "media3.common",
    "androidx.media3.effect": "media3.effect",
    "androidx.media3.exoplayer": "media3.exoplayer",
    "androidx.media3.transformer": "media3.transformer",
    "androidx.media3.ui": "media3.ui",
    "androidx.navigation": "androidx.navigation.compose",
}

# Imported from the platform, not from a library.
# org.json ships in the Android framework, not as a dependency.
PLATFORM_PREFIXES = ("android.", "java.", "javax.", "kotlin.", "kotlinx.", "org.json.", "com.squish.")
# Pulled in by Media3 itself and used through it.
ALLOWED_TRANSITIVE = ("com.google.common.",)

IMPORT = re.compile(r"^import\s+([A-Za-z0-9_.]+)", re.MULTILINE)
DECLARED = re.compile(r"(?:implementation|debugImplementation|api)\(libs\.([A-Za-z0-9_.]+)\)")


def provider_of(fqn: str) -> str | None:
    """The alias expected to provide this import, by longest matching prefix."""
    best = None
    for prefix, alias in PROVIDED_BY.items():
        if fqn == prefix or fqn.startswith(prefix + "."):
            if best is None or len(prefix) > len(best[0]):
                best = (prefix, alias)
    return best[1] if best else None


def main() -> int:
    if not MODULE_BUILD.exists():
        print(f"{MODULE_BUILD} not found")
        return 1

    declared = set(DECLARED.findall(MODULE_BUILD.read_text()))

    needed: dict[str, str] = {}      # alias -> an import that needs it
    unmapped: dict[str, str] = {}    # import prefix -> the file that used it

    for path in sorted(SOURCE.rglob("*.kt")):
        for fqn in IMPORT.findall(path.read_text()):
            if fqn.startswith(PLATFORM_PREFIXES) or fqn.startswith(ALLOWED_TRANSITIVE):
                continue
            alias = provider_of(fqn)
            if alias is None:
                unmapped.setdefault(".".join(fqn.split(".")[:3]), str(path))
            else:
                needed.setdefault(alias, fqn)

    problems = []
    for alias, example in sorted(needed.items()):
        if alias not in declared:
            problems.append(
                f"imports {example} but app/build.gradle.kts never declares libs.{alias} "
                f"— it is only arriving behind something else"
            )
    for prefix, path in sorted(unmapped.items()):
        problems.append(f"{path}: imports {prefix}.*, which this checker has no entry for")

    if problems:
        for problem in problems:
            print(problem)
        return 1
    print(f"every imported library is declared ({len(needed)} of them)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
